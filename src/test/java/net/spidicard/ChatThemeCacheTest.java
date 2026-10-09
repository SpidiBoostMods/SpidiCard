package net.spidicard;
import net.minecraft.text.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class ChatThemeCacheTest {
    @Test void rendererOwnershipCacheStaysBoundedWithTwentyThousandLiveTextObjects(){
        var keep=new ArrayList<OrderedText>();for(int i=0;i<20000;i++){int point=i;OrderedText text=visitor->visitor.accept(0,Style.EMPTY,point);keep.add(text);assertSame(text,ChatTheme.animate(text));assertTrue(ChatTheme.ownershipCacheSize()<=512);}
        assertEquals(20000,keep.size());
    }
    @Test void ownedGradientStillAnimatesAfterCacheEviction(){
        Style themed=Style.EMPTY.withFont(ChatTheme.PREFIX).withColor(0xAA71FF);OrderedText text=visitor->visitor.accept(0,themed,'S');assertNotSame(text,ChatTheme.animate(text));
        var observed=new ArrayList<Style>();ChatTheme.animate(text,0).accept((i,s,p)->{observed.add(s);return true;});assertEquals(Style.DEFAULT_FONT_ID,observed.getFirst().getFont());assertNotEquals(themed.getColor(),observed.getFirst().getColor());
    }
}
