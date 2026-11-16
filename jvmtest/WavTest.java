import com.marinevhf.app.WavWriter;
import java.io.*;
public class WavTest { public static void main(String[] a) throws Exception {
  File f=File.createTempFile("wavt",".wav"); RandomAccessFile r=new RandomAccessFile(f,"rw");
  WavWriter w=new WavWriter(r.getChannel(),48000); short[] s=new short[1000]; for(int i=0;i<1000;i++)s[i]=(short)(i*10);
  w.write(s,1000); w.write(s,500); w.close(48000);
  byte[] b=java.nio.file.Files.readAllBytes(f.toPath());
  System.out.println("len="+b.length+" data="+((b[40]&255)|(b[41]&255)<<8|(b[42]&255)<<16)+" riff="+((b[4]&255)|(b[5]&255)<<8|(b[6]&255)<<16));
}}
