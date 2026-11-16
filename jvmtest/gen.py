import sys
sys.path.insert(0,'/home/claude/marine_vhf')
import numpy as np
from sim import SimSdr, demo_scene
import ais, dsp
from marine_channels import ship_hz

def to_u8(x):
    out=np.empty(len(x)*2,np.uint8)
    out[0::2]=np.clip(np.round(x.real*127.5+127.5),0,255); out[1::2]=np.clip(np.round(x.imag*127.5+127.5),0,255)
    return out
def deq(u): 
    f=(u.astype(np.float32)-127.5)/127.5
    return (f[0::2]+1j*f[1::2]).astype(np.complex64)

# --- АИС
sdr=SimSdr(demo_scene(ais_amp=0.03, ais_off_hz=2000), seed=5); sdr.center_freq=162e6
nb=int(10*1.92e6/dsp.BLOCK)
raw=[to_u8(sdr.read_samples(dsp.BLOCK)) for _ in range(nb)]
open('/tmp/jtest/ais.cu8','wb').write(np.concatenate(raw).tobytes())
dec=ais.AisDecoder(); lines=[]
for b,u in enumerate(raw):
    for m in dec.process(deq(u)):
        for s in ais.nmea_sentences(m['bits'], m['chan']): lines.append(f"{b+1} {m['chan']} {s}")
open('/tmp/jtest/ais.ref','w').write("\n".join(lines)+"\n")
print("AIS ref sentences:", len(lines))

# --- голос (канал 16)
f=ship_hz(16)
sdr=SimSdr(demo_scene(), seed=6); sdr.center_freq=f-dsp.VOICE_OFFSET_HZ
nb=int(7*1.92e6/dsp.BLOCK)
raw=[to_u8(sdr.read_samples(dsp.BLOCK)) for _ in range(nb)]
open('/tmp/jtest/voice.cu8','wb').write(np.concatenate(raw).tobytes())
v=dsp.NfmVoice(); lines=[]; aud=[]
for b,u in enumerate(raw):
    a,info=v.process(deq(u), 3)
    aud.append(a)
    lines.append(f"{b} {info['noise']:.4f} {int(info['open'])} {info['rssi']:.2f}")
open('/tmp/jtest/voice.ref','w').write("\n".join(lines)+"\n")
np.concatenate(aud).astype('<f4').tofile('/tmp/jtest/voice_audio.ref')
print("voice blocks:", nb)
