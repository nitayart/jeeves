package dev.workbench.engine.index;

import dev.workbench.registry.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic host fixture: no JEB instance, model, HTTP server, or existing database. */
public final class SearchIndexPersistenceIT {
    private static final String A="Lfixture/A;", B="Lfixture/B;";
    private static int checks;
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
        checks++; System.out.println("PASS "+message);
    }
    private static void await(CountDownLatch event,String message) throws Exception {
        check(event.await(10,TimeUnit.SECONDS),message);
    }
    private static void stopped() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        for (;;) {
            // Join the entire snapshot before taking another. Testing isAlive() on
            // an old snapshot could miss a successor started by workerFinished:
            // its predecessor had exited, but the successor wasn't in the snapshot.
            var workers=Thread.getAllStackTraces().keySet().stream()
                    .filter(t->t.getName().startsWith("search-index")).toList();
            if(workers.isEmpty()) return;
            for(Thread worker:workers) {
                long remaining=deadline-System.nanoTime();
                if(remaining<=0) throw new AssertionError("index worker did not stop");
                worker.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
        }
    }
    private static final class Host implements SearchIndex.Host {
        final AtomicInteger version=new AtomicInteger(1);
        final AtomicInteger calls=new AtomicInteger();
        volatile int failCall=-1, blockCall=-1, blockLabelCall=-1;
        volatile CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        @Override public List<String[]> classSnapshot(){return List.of(new String[]{A,"fixture.A"},new String[]{B,"fixture.B"});}
        @Override public String decompileText(String address) {
            if(!A.equals(address)) return "class B {}";
            int call=calls.incrementAndGet();
            String text="class A { int version = "+version.get()+"; }";
            if(call==blockCall) {
                entered.countDown();
                try { release.await(); } catch(InterruptedException interrupted){return null;}
            }
            if(call==failCall) return null;
            return text;
        }
        @Override public String classLabel(String address) {
            if(A.equals(address) && calls.get()==blockLabelCall && Thread.currentThread().getName().startsWith("search-index")) {
                entered.countDown();
                try { release.await(); } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
            return A.equals(address)?"fixture.A":"fixture.B";
        }
        @Override public void evictDecompiled(String address){}
        @Override public Map<String,Object> indexSourceIdentity(){return Map.of("measured",true,"fixture","pending-index","analysis_classes_at_open",2);}
        @Override public long jdb2Mtime(){return 1L;}
    }
    private static SearchIndex built(Host host,Path dir) throws Exception {
        SearchIndex index=new SearchIndex(host,"fixture",dir.toFile(),"test");
        index.start(); stopped();
        check("built".equals(index.status().get("state")),"initial fixture index builds");
        return index;
    }
    private static List<?> pending(Path dir) throws Exception {
        return (List<?>)Json.parse(Files.readString(dir.resolve("pending.json")));
    }
    private static boolean current(SearchIndex index,int version) {
        return index.entries().stream().anyMatch(e->A.equals(e.addr()) && String.join("\n",e.lines()).contains("version = "+version));
    }
    private static void heals(Path dir,int version) throws Exception {
        Host host=new Host(); host.version.set(version);
        SearchIndex reopened=new SearchIndex(host,"reopened",dir.toFile(),"test");
        try {
            check(reopened.tryWarmLoad()>0,"reopen adopts valid retained corpus"); stopped();
            check(current(reopened,version),"reopen drains preserved pending work into fresh text");
            check(pending(dir).isEmpty(),"pending clears after replacement text and header persist");
        } finally { reopened.kill(); stopped(); }
    }
    private static void interrupted(Path root) throws Exception {
        Path dir=root.resolve("interrupted"); Host host=new Host(); SearchIndex index=built(host,dir);
        try {
            host.version.set(2); host.blockCall=host.calls.get()+1;
            index.invalidate(A,A); await(host.entered,"invalidation decompile is in flight");
            index.kill(); host.release.countDown(); stopped();
            check(pending(dir).contains(A),"cancelled decompile preserves its durable pending entry even if host clears interrupt");
        } finally { index.kill(); host.release.countDown(); stopped(); }
        heals(dir,2);
    }
    private static void failedText(Path root) throws Exception {
        Path dir=root.resolve("failed-text"); Host host=new Host(); SearchIndex index=built(host,dir);
        try {
            host.version.set(2); host.failCall=host.calls.get()+1;
            index.invalidate(A,A); stopped();
            check(pending(dir).contains(A),"unsuccessful decompile retains pending work");
            check(Boolean.TRUE.equals(index.status().get("partialCoverage")),"unindexed pending work reports partial coverage");
        } finally { index.kill(); stopped(); }
        heals(dir,2);
    }
    private static void failedWrite(Path root) throws Exception {
        Path dir=root.resolve("failed-write"); Host host=new Host(); SearchIndex index=built(host,dir);
        Path blocked=dir.resolve("classes").resolve(SearchIndex.encodeAddr(A)+".gz");
        try {
            host.version.set(2); host.blockLabelCall=host.calls.get()+1;
            index.invalidate(A,A); await(host.entered,"decompile completed before forced corpus write failure");
            Files.createDirectory(blocked); Files.writeString(blocked.resolve("blocker"),"fixture");
            host.release.countDown(); stopped();
            check(pending(dir).contains(A),"failed compressed-text replacement keeps pending work");
            check(Boolean.TRUE.equals(index.status().get("partialCoverage")),"failed replacement does not claim complete coverage");
        } finally {
            index.kill(); host.release.countDown(); stopped();
            Files.deleteIfExists(blocked.resolve("blocker")); Files.deleteIfExists(blocked);
        }
        heals(dir,2);
    }
    private static void newerInvalidation(Path root) throws Exception {
        Path dir=root.resolve("newer-edit"); Host host=new Host(); SearchIndex index=built(host,dir);
        try {
            host.version.set(2); host.blockLabelCall=host.calls.get()+1;
            index.invalidate(A,A); await(host.entered,"old text is waiting after its decompile");
            CountDownLatch oldRelease=host.release;
            host.version.set(3); host.blockLabelCall=-1; host.blockCall=host.calls.get()+1;
            host.entered=new CountDownLatch(1); host.release=new CountDownLatch(1);
            index.invalidate(A,A); oldRelease.countDown();
            await(host.entered,"newer invalidation receives its own decompile");
            check(!current(index,2),"superseded decompile cannot publish stale text");
            check(pending(dir).contains(A),"superseded decompile cannot clear the newer pending entry");
            host.release.countDown(); stopped();
            check(current(index,3),"latest text is indexed after the newer edit");
            check(pending(dir).isEmpty(),"latest text and header commit clears pending work");
        } finally { index.kill(); host.release.countDown(); stopped(); }
        heals(dir,3);
    }

    private static void failedHeader(Path root) throws Exception {
        Path dir=root.resolve("failed-header"); Host host=new Host(); SearchIndex index=built(host,dir);
        Path header=dir.resolve("header.json"), backup=dir.resolve("original-header.json");
        try {
            host.version.set(2); host.blockLabelCall=host.calls.get()+1;
            index.invalidate(A,A); await(host.entered,"replacement is held before header failure injection");
            Files.move(header,backup); Files.createDirectory(header);
            Files.writeString(header.resolve("blocker"),"fixture");
            host.release.countDown(); stopped();
            check(pending(dir).contains(A),"successful gzip cannot clear pending when header persistence fails");
            check(Boolean.TRUE.equals(index.status().get("partialCoverage")),"failed header commit reports partial coverage");
        } finally {
            index.kill(); host.release.countDown(); stopped();
            Files.deleteIfExists(header.resolve("blocker")); Files.deleteIfExists(header);
            Files.move(backup,header);
        }
        heals(dir,2);
    }

    private static void onDemand(Path root) throws Exception {
        Path dir=root.resolve("on-demand"); Host host=new Host();
        SearchIndex index=new SearchIndex(host,"first-edit",dir.toFile(),"test");
        index.invalidate(A,A); stopped();
        Map<?,?> header=(Map<?,?>)Json.parse(Files.readString(dir.resolve("header.json")));
        check(host.indexSourceIdentity().equals(header.get("sourceIdentity")),"first on-demand edit records measured source identity without a full build");
        check(Boolean.FALSE.equals(header.get("completeCoverage")),"on-demand header explicitly records incomplete coverage");
        index.kill(); stopped();
        SearchIndex reopened=new SearchIndex(host,"second-session",dir.toFile(),"test");
        check(reopened.tryWarmLoad()>0,"edit-only corpus survives reopen");
        check(current(reopened,1),"prior session text is searchable after reopen");
        check(Boolean.TRUE.equals(reopened.status().get("partialCoverage")),"warm adoption does not promote partial corpus to complete");
        check(((Number)reopened.status().get("total")).intValue()==2,"reopen retains full analysis class count");
        host.version.set(2); reopened.invalidate(A,A); stopped();
        check(current(reopened,2) && !current(reopened,1),"second session edit replaces old cached text");
        reopened.kill(); stopped();
        // Legacy identity must be regenerated, never blessed with a new hash.
        Map<String,Object> broken=new java.util.LinkedHashMap<>();
        ((Map<?,?>)Json.parse(Files.readString(dir.resolve("header.json")))).forEach((k,v)->broken.put(k.toString(),v));
        broken.put("sourceIdentity",Map.of()); Files.writeString(dir.resolve("header.json"),Json.write(broken));
        SearchIndex rejected=new SearchIndex(host,"legacy",dir.toFile(),"test");
        check(rejected.recoveryCandidates().contains(A),"rejected header preserves addresses as regeneration candidates");
        check(rejected.tryWarmLoad()<0 && rejected.entries().isEmpty(),"identity-less corpus cannot hydrate stale text");
        host.version.set(3); rejected.invalidateAll(rejected.recoveryCandidates()); stopped();
        check(current(rejected,3) && !current(rejected,2),"legacy recovery decompiles current model text");
        rejected.kill(); stopped();
        heals(dir,3);
    }

    private static void missingGzip(Path root) throws Exception {
        Path dir=root.resolve("missing-gzip"); Host host=new Host(); SearchIndex index=built(host,dir);
        index.kill(); stopped();
        Files.delete(dir.resolve("classes").resolve(SearchIndex.encodeAddr(A)+".gz"));
        SearchIndex reopened=new SearchIndex(host,"missing",dir.toFile(),"test");
        check(reopened.tryWarmLoad()>0,"missing gzip is detected during hydration, not hidden by header adoption");
        host.blockCall=host.calls.get()+1;
        reopened.entries(); await(host.entered,"missing gzip is queued for regeneration");
        check(Boolean.TRUE.equals(reopened.status().get("partialCoverage")),"missing text reports partial coverage while recovering");
        host.release.countDown(); stopped();
        check(current(reopened,1),"missing gzip is regenerated from the current model");
        reopened.kill(); stopped();
    }

    private static void rejectedWorker(Path root) throws Exception {
        Path dir=root.resolve("rejected-worker"); Host host=new Host(); SearchIndex index=built(host,dir);
        host.version.set(2); host.blockCall=host.calls.get()+1;
        index.invalidate(A,A); await(host.entered,"old generation is in flight before warm rejection");
        index.rejectWarm("fixture foreign database change");
        host.version.set(3); index.invalidateAll(List.of(A,B)); host.release.countDown(); stopped();
        check(current(index,3) && !current(index,2),"rejected generation cannot commit old text or steal new recovery work");
        check(pending(dir).isEmpty(),"new recovery worker receives the queue after superseded worker exits");
        index.kill(); stopped();
    }

    private static void immediateQuery(Path root) throws Exception {
        Path dir=root.resolve("immediate-query"); Host host=new Host(); SearchIndex index=built(host,dir);
        try {
            host.version.set(2); host.blockCall=host.calls.get()+1;
            index.invalidate(A,A); await(host.entered,"background refresh is held after invalidation");
            check(!current(index,1),"stale text disappears immediately");
            var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
                for(int n=0;n<8;n++) tasks.add(()->index.queryEntries().stream()
                        .anyMatch(e->A.equals(e.addr()) && String.join("\n",e.lines()).contains("version = 2")));
                for(var result:pool.invokeAll(tasks)) check(result.get(),"search sees renamed text while background refresh is held");
            } finally {pool.shutdownNow();}
            check(pending(dir).isEmpty(),"query persists replacement text and header while background render waits");
            host.release.countDown(); stopped();
            check(current(index,2) && pending(dir).isEmpty(),"background persistence still completes normally");
        } finally {index.kill();host.release.countDown();stopped();}
    }

    private static void demandOnly(Path root) throws Exception {
        Path dir=root.resolve("demand-only"); Host host=new Host();
        SearchIndex index=new SearchIndex(host,"demand",dir.toFile(),"test",false);
        try {
            index.invalidate(A,A); stopped();
            check(host.calls.get()==0 && !Boolean.TRUE.equals(index.status().get("workerRunning")),
                    "on-demand invalidation never starts native background work");
            check(pending(dir).contains(A),"on-demand invalidation durably records pending work");
            index.cancel(); // Cancelling unsolicited background work must not disable search.
            host.version.set(2);
            var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
                for(int n=0;n<8;n++) tasks.add(()->index.queryEntries().stream()
                        .anyMatch(e->A.equals(e.addr()) && String.join("\n",e.lines()).contains("version = 2")));
                for(var result:pool.invokeAll(tasks)) check(result.get(),"concurrent on-demand reader sees current names");
            } finally {pool.shutdownNow();}
            check(host.calls.get()==1,"eight readers share exactly one current render");
            check(pending(dir).isEmpty(),"on-demand search persists text and header before clearing pending");
            index.queryEntries(); check(host.calls.get()==1,"repeat search reuses the durable fresh cache");
            host.version.set(3); index.invalidate(A,A);
        } finally {index.kill();stopped();}
        SearchIndex reopened=new SearchIndex(host,"demand-reopened",dir.toFile(),"test",false);
        try {
            int before=host.calls.get();
            check(reopened.tryWarmLoad()>0,"on-demand reopen retains the corpus and pending queue"); stopped();
            check(host.calls.get()==before,"on-demand warm adoption does not render queued classes");
            check(reopened.queryEntries().stream().anyMatch(e->String.join("\n",e.lines()).contains("version = 3")),
                    "first search after reopen replaces stale saved names");
            check(pending(dir).isEmpty(),"reopened on-demand search durably clears pending");
            // A render that overlaps a newer edit must not publish its obsolete names.
            host.version.set(4); reopened.invalidate(A,A); host.blockCall=host.calls.get()+1;
            var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var result=worker.submit(reopened::queryEntries); await(host.entered,"query render overlaps a rename");
                host.version.set(5); reopened.invalidate(A,A); host.release.countDown();
                check(result.get(10,TimeUnit.SECONDS).stream().noneMatch(e->String.join("\n",e.lines()).contains("version = 4")),
                        "query cannot publish text superseded by a newer rename");
                check(pending(dir).contains(A),"superseded query leaves the newer rename pending");
                reopened.queryEntries();check(current(reopened,5),"next query commits the latest rename");
            } finally {host.release.countDown();worker.shutdownNow();}
            host.version.set(6); reopened.invalidate(A,A); host.failCall=host.calls.get()+1;
            check(reopened.queryEntries().stream().noneMatch(e->A.equals(e.addr())),"failed demand render never exposes stale text");
            check(pending(dir).contains(A),"failed demand render remains retryable");
            reopened.queryEntries(); check(current(reopened,6) && pending(dir).isEmpty(),"later demand query recovers failed render");
            Path header=dir.resolve("header.json"),backup=dir.resolve("header-before-failure.json");
            Files.move(header,backup);Files.createDirectory(header);Files.writeString(header.resolve("blocker"),"fixture");
            try {
                host.version.set(7);reopened.invalidate(A,A);int beforeHeader=host.calls.get();
                check(reopened.queryEntries().stream().anyMatch(e->String.join("\n",e.lines()).contains("version = 7")),
                        "failed demand header commit can return freshly rendered text");
                check(pending(dir).contains(A),"failed demand header commit retains durable pending work");
                Files.delete(header.resolve("blocker"));Files.delete(header);Files.move(backup,header);
                reopened.queryEntries();
                check(host.calls.get()==beforeHeader+1,"retrying demand header persistence reuses the already rendered text");
                check(pending(dir).isEmpty(),"successful demand header retry clears pending work");
            } finally {
                if(Files.isDirectory(header)){Files.deleteIfExists(header.resolve("blocker"));Files.delete(header);}
                if(Files.exists(backup))Files.move(backup,header,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            reopened.start();stopped();check("built".equals(reopened.status().get("state")),"explicit full rebuild still works in on-demand mode");
        } finally {reopened.kill();stopped();}
    }

    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("jeeves-index-persistence-");
        try { interrupted(root); failedText(root); failedWrite(root); newerInvalidation(root); failedHeader(root); onDemand(root); missingGzip(root); rejectedWorker(root); immediateQuery(root); demandOnly(root); }
        catch(Exception|AssertionError failure){failure.printStackTrace(System.out);throw failure;}
        System.out.println("Search-index persistence checks passed: "+checks);
    }
}
