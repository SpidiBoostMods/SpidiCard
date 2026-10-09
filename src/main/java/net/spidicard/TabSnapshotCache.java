package net.spidicard;

import java.util.*;
import java.util.function.*;

/** TAB membership changes on its packet revision, not on every inventory packet or frame. */
public final class TabSnapshotCache {
    public record Entry(UUID id,String name) {}
    public record Snapshot(long fingerprint,boolean present,int eligible) {}
    private Object connection;
    private long epoch,revision;
    private String self;
    private Snapshot value;
    public Snapshot get(Object connection,long epoch,long revision,String self,
                        Supplier<List<Entry>> entries,Predicate<String> eligible) {
        if(value!=null&&this.connection==connection&&this.epoch==epoch&&this.revision==revision&&Objects.equals(this.self,self))return value;
        List<Entry> tab=entries.get();
        long fingerprint=tab.stream().map(e->e.id()+":"+e.name()).sorted().toList().hashCode();
        int count=(int)tab.stream().map(Entry::name).filter(eligible).count();
        this.connection=connection;this.epoch=epoch;this.revision=revision;this.self=self;
        return value=new Snapshot(fingerprint,!tab.isEmpty(),count);
    }
    public void clear(){connection=null;self=null;value=null;}
}
