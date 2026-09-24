package dev.betterlitematica.core;

import java.io.IOException;
import java.util.BitSet;

/** Built alongside source validation on the IO worker. One occupancy bit per source cell. */
public final class SourceBlockIndex {
    private final Vec3i size;private final long[] occupied;private final BitSet sections;private final long count;
    private SourceBlockIndex(Vec3i size,long[] occupied,BitSet sections,long count){this.size=size;this.occupied=occupied;this.sections=sections;this.count=count;}
    public static SourceBlockIndex build(PackedBits blocks,Vec3i size,boolean[] air,Cancellation cancel)throws IOException{
        if((long)size.x()*size.y()*size.z()!=blocks.size()||blocks.size()>536_870_912)throw new IllegalArgumentException("Source index dimensions");
        long[] occupied=new long[(blocks.size()+63)>>>6];var sections=new BitSet();long count=0;int nx=(size.x()+15)/16,nz=(size.z()+15)/16;
        for(int i=0;i<blocks.size();i++){if((i&8191)==0)cancel.check();int id=blocks.get(i);if(id>=air.length)throw new IOException("Palette index outside source palette");
            if(air[id])continue;occupied[i>>>6]|=1L<<(i&63);count++;int x=i%size.x(),z=i/size.x()%size.z(),y=i/(size.x()*size.z());sections.set((x>>>4)+(z>>>4)*nx+(y>>>4)*nx*nz);
        }
        return new SourceBlockIndex(size,occupied,sections,count);
    }
    public long count(){return count;}
    public boolean sectionEmpty(SectionKey key){return !sections.get(key.x()+key.z()*((size.x()+15)/16)+key.y()*((size.x()+15)/16)*((size.z()+15)/16));}
    /** Section-local YZX cursor; each word scan is clipped to the current 16-cell source row. */
    public int next(SectionKey key,int cursor){
        if(cursor<0||cursor>4096)throw new IndexOutOfBoundsException();
        while(cursor<4096){int x=key.x()*16+(cursor&15),y=key.y()*16+(cursor>>>8),z=key.z()*16+((cursor>>>4)&15);if(y>=size.y())return 4096;
            int rowEnd=(cursor&~15)+16;
            if(z>=size.z()||x>=size.x()){cursor=rowEnd;continue;}
            int available=Math.min(rowEnd-cursor,size.x()-x),from=x+z*size.x()+y*size.x()*size.z(),until=from+available,word=from>>>6;
            long bits=occupied[word]&(-1L<<(from&63));
            while(true){if(bits!=0){int found=(word<<6)+Long.numberOfTrailingZeros(bits);if(found<until)return cursor+found-from;break;}if(++word>((until-1)>>>6))break;bits=occupied[word];}
            cursor=rowEnd;
        }
        return 4096;
    }
    public long estimatedBytes(){return 64L+occupied.length*8L+sections.size()/8L;}
}
