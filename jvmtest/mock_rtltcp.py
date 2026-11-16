"""Имитация rtl_tcp: заголовок, команды (смена частоты), поток u8 IQ из симулятора эфира."""
import sys, socket, struct, threading
sys.path.insert(0,'/home/claude/marine_vhf')
import numpy as np
from sim import SimSdr, demo_scene
from dsp import BLOCK
port=int(sys.argv[1])
srv=socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR,1)
srv.bind(('127.0.0.1',port)); srv.listen(1)
c,_=srv.accept()
c.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)
c.sendall(b'RTL0'+struct.pack('>II',5,29))
sdr=SimSdr(demo_scene(ais_amp=0.03, ais_off_hz=1500), seed=11); sdr.center_freq=100e6
cmds=[]
def reader():
    buf=b''
    while True:
        d=c.recv(4096)
        if not d: break
        buf+=d
        while len(buf)>=5:
            code,val=buf[0],struct.unpack('>I',buf[1:5])[0]; buf=buf[5:]
            if code==1: sdr.center_freq=val
            cmds.append((code,val))
threading.Thread(target=reader,daemon=True).start()
try:
    while True:
        x=sdr.read_samples(BLOCK)
        u=np.empty(2*BLOCK,np.uint8)
        u[0::2]=np.clip(np.round(x.real*127.5+127.5),0,255); u[1::2]=np.clip(np.round(x.imag*127.5+127.5),0,255)
        c.sendall(u.tobytes())
except (BrokenPipeError, ConnectionResetError):
    pass
print("mock: команды от клиента:", cmds[:12], "всего", len(cmds), flush=True)
