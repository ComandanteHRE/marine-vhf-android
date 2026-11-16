import com.marinevhf.app.NmeaServer;
import java.net.*; import java.io.*;
public class NmeaTest { public static void main(String[] a) throws Exception {
  DatagramSocket u=new DatagramSocket(10110); u.setSoTimeout(2000);
  NmeaServer n=new NmeaServer(10111,"127.0.0.1",10110); n.start();
  Socket c=new Socket("127.0.0.1",10111); Thread.sleep(200);
  n.send("!AIVDM,1,1,,A,15M67FC000G?ufbE`FepT@3n00Sa,0*5C");
  byte[] buf=new byte[200]; DatagramPacket p=new DatagramPacket(buf,200); u.receive(p);
  System.out.println("UDP: "+new String(buf,0,p.getLength()).trim());
  System.out.println("TCP: "+new BufferedReader(new InputStreamReader(c.getInputStream())).readLine());
  System.out.println("clients="+n.clientCount()); n.stop();
}}
