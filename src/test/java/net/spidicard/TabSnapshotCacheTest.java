package net.spidicard;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class TabSnapshotCacheTest {
    private final List<TabSnapshotCache.Entry> players=java.util.stream.IntStream.range(0,300).mapToObj(i->new TabSnapshotCache.Entry(new UUID(0,i),"Player"+i)).toList();
    @Test void inventoryAndFramePumpsDoNotRebuildAnUnchangedThreeHundredPlayerTab(){
        var cache=new TabSnapshotCache();var reads=new AtomicInteger();var connection=new Object();
        TabSnapshotCache.Snapshot first=null;
        for(int i=0;i<10000;i++){var view=cache.get(connection,1,5,"Player0",()->{reads.incrementAndGet();return players;},n->!n.equals("Player0"));if(first==null)first=view;else assertSame(first,view);}
        assertEquals(1,reads.get());assertEquals(299,first.eligible());assertTrue(first.present());assertEquals(players.stream().map(e->e.id()+":"+e.name()).sorted().toList().hashCode(),first.fingerprint());
    }
    @Test void tabRevisionWorldConnectionAndCurrentNicknameEachInvalidateTheSnapshot(){
        var cache=new TabSnapshotCache();var reads=new AtomicInteger();var a=new Object();java.util.function.Supplier<List<TabSnapshotCache.Entry>> supply=()->{reads.incrementAndGet();return players;};
        cache.get(a,1,5,"Player0",supply,n->true);cache.get(a,1,6,"Player0",supply,n->true);cache.get(a,2,6,"Player0",supply,n->true);cache.get(new Object(),2,6,"Player0",supply,n->true);cache.get(a,2,6,"Player1",supply,n->true);assertEquals(5,reads.get());cache.clear();cache.get(a,2,6,"Player1",supply,n->true);assertEquals(6,reads.get());
    }
    @Test void membershipChangesAndEmptyTabRemainVisibleToFullLoadingChecks(){
        var cache=new TabSnapshotCache();var c=new Object();var full=cache.get(c,1,1,"",()->players,n->true);var empty=cache.get(c,1,2,"",List::of,n->true);assertFalse(empty.present());assertEquals(0,empty.eligible());assertNotEquals(full.fingerprint(),empty.fingerprint());
    }
}
