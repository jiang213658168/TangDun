"""
糖盾 完整验证套件 v3 — 12场景 x 5轮 + R1-R7门控 + 陡降保护
"""
import numpy as np
import onnxruntime as ort
sess = ort.InferenceSession(r'D:\tangdun\android\app\src\main\assets\model_curve_v2.onnx')

def ext_features(gh, idx, bh, ch):
    start = max(0, idx - 288); history = gh[start:idx]
    if len(history) < 10: return [0.0]*15
    mean = float(np.mean(history)); std = float(np.std(history, ddof=1)) if len(history)>1 else 1.0
    if std <= 0: std = 1.0
    f1=(gh[idx]-mean)/std; f2=(gh[idx]-gh[idx-1])/std if idx>=1 else 0
    f3=(gh[idx]-gh[idx-3])/std if idx>=3 else 0; f4=(gh[idx]-gh[idx-6])/std if idx>=6 else 0
    f5=(gh[idx]-gh[idx-12])/std if idx>=12 else 0; f6=f4/30.0; f7=f5/60.0
    r72=history[-72:] if len(history)>=72 else history
    f8=(np.mean(r72)-mean)/std; f9=np.std(r72,ddof=1)/std if len(r72)>1 else 0.0
    f10=float(np.sum(bh[max(0,idx-48):idx+1]))/20.0
    br=bh[max(0,idx-144):idx+1]; bi=np.where(br>0)[0]
    f11=min((len(br)-1-bi[-1])*5.0/120.0,1.0) if len(bi)>0 else 1.0
    f12=float(np.sum(ch[max(0,idx-48):idx+1]))/100.0
    cr=ch[max(0,idx-144):idx+1]; ci=np.where(cr>0)[0]
    f13=min((len(cr)-1-ci[-1])*5.0/120.0,1.0) if len(ci)>0 else 1.0
    return [f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13,0.0,0.0]

def tcn(gh,bh,ch,npts=37):
    idx=len(gh)-1; feats=ext_features(gh,idx,bh,ch)
    x=np.array([feats],dtype=np.float32); out=sess.run(None,{'input':x})[0]
    a,b,c,d=out[0]; G0=gh[idx]
    curve=[G0*(1+a*(i/(npts-1))**3+b*(i/(npts-1))**2+c*(i/(npts-1))+d) for i in range(npts)]
    offset=curve[0]-G0
    return [max(1.0,min(30.0,v-offset)) for v in curve], (a,b,c,d), feats

def dalla(G0, meals, ins, w=65, isf=1.5, fast=7.0, basal=8.0, sigma=0.0, act=0.5, H=180):
    isfF=max(0.3,min(3.0,1.5/max(0.5,min(6.0,isf))))
    kSt=max(0.025,min(0.045,0.035-isfF*0.004)); VmxG=max(5.0,min(12.0,10.0-isfF*2.0))
    VgPK=max(1.4,min(2.0,1.6+(w-65)*0.01)); k1=max(0.040,min(0.090,0.060+act*0.030))
    Vm0=max(1.5,min(4.0,2.5-isfF*0.2+act*0.3)); VmX=max(0.02,min(0.10,0.05-isfF*0.01))
    Km0=100.0; hB=max(1.5,min(3.0,2.07+isfF*0.4)); rT=max(8.0,min(12.0,8.0+fast*0.3))
    kp3=max(0.020,min(0.050,0.045-isfF*0.007)); kp2=max(0.035,min(0.065,0.060-isfF*0.007))
    ka1=0.024; ka2=0.024; ke=0.138; kG=0.050; rCl=0.005; fC=0.9; Gb=fast; Ib=basal
    Vg=max(60.0,min(300.0,w*VgPK)); Vi=max(2.0,min(25.0,w*0.05)); Vg18=Vg*18.0
    G=G0; sq1=0.0; sq2=0.0
    for T,u in ins:
        mU=u*100.0; sq1+=mU*np.exp(-ka1*T)
        if abs(ka2-ka1)>1e-6: sq2+=mU*ka1/(ka2-ka1)*(np.exp(-ka1*T)-np.exp(-ka2*T))
        else: sq2+=mU*ka1*T*np.exp(-ka1*T)
    Gexc=max(0.0,G-Gb); insF=ka2*sq2/(ke*Vi); endo=sigma*Gexc/ke
    I=basal+insF+endo; I=max(basal*0.5,min(basal*8.0,I))
    iob_in=sum(u*0.5**(T/55.0) for T,u in ins if T<240)
    ci=max(iob_in*15.0,5.0)
    if ci>I*1.5: I=min(ci,basal*8.0)
    X=max(0.0,min(5.0,(I-basal)/basal)); XL=X
    stomach=0.0; gut=0.0
    for T,c in meals:
        mg=c*1000*fC; stomach+=mg*np.exp(-kSt*T)
        if abs(kG-kSt)>1e-6: gut+=mg*kSt/(kG-kSt)*(np.exp(-kSt*T)-np.exp(-kG*T))
        else: gut+=mg*kSt*T*np.exp(-kSt*T)
    gut=min(gut,VmxG*w/kG)
    dt=5.0; n=H//5; curve=[]
    def d(s):
        G,I,X,XL,st,gu,sq1,sq2=s; Ra=kG*gu/Vg18; hS=XL/(1+XL)
        EGP=hB*(1-hS)*w/Vg18; Uii=k1*(G-Gb); Gm=G*18; Vm=Vm0+VmX*X
        Uid=Vm*Gm/(Km0+Gm)*w/Vg18; Ren=rCl*(G-rT)*18 if G>rT else 0.0
        dG=Ra+EGP-Uii-Uid-Ren; dI=ka2*sq2/Vi-ke*I+sigma*max(0.0,G-Gb)
        iDr=max(0.0,I-Ib)/Ib; dX=-kp3*X+kp3*iDr; dXL=-kp2*XL+kp2*iDr
        gR=kSt*st; gM=VmxG*w; dSt=-min(gR,gM); dGu=min(gR,gM)-kG*gu
        dsq1=-ka1*sq1; dsq2=ka1*sq1-ka2*sq2
        return np.array([dG,dI,dX,dXL,dSt,dGu,dsq1,dsq2])
    for step in range(n+1):
        curve.append(max(1.0,min(30.0,G)))
        if step==n: break
        s=np.array([G,I,X,XL,stomach,gut,sq1,sq2])
        k1v=d(s); s2=s+0.5*dt*k1v; k2v=d(s2); s3=s+0.5*dt*k2v; k3v=d(s3); s4=s+dt*k3v; k4v=d(s4)
        G=max(1.5,min(28.0,s[0]+dt/6*(k1v[0]+2*k2v[0]+2*k3v[0]+k4v[0])))
        I=max(0.5,min(basal*8.0,s[1]+dt/6*(k1v[1]+2*k2v[1]+2*k3v[1]+k4v[1])))
        X=max(0.0,min(5.0,s[2]+dt/6*(k1v[2]+2*k2v[2]+2*k3v[2]+k4v[2])))
        XL=max(0.0,min(5.0,s[3]+dt/6*(k1v[3]+2*k3v[3]+2*k3v[3]+k4v[3])))
        stomach=max(0.0,s[4]+dt/6*(k1v[4]+2*k2v[4]+2*k3v[4]+k4v[4]))
        gut=max(0.0,s[5]+dt/6*(k1v[5]+2*k2v[5]+2*k3v[5]+k4v[5]))
        sq1=max(0.0,s[6]+dt/6*(k1v[6]+2*k2v[6]+2*k3v[6]+k4v[6]))
        sq2=max(0.0,s[7]+dt/6*(k1v[7]+2*k2v[7]+2*k3v[7]+k4v[7]))
    return curve

def gate(tcn_raw, dm, G0, iob, c2h):
    """R1-R7 门控 + 陡降保护"""
    n=min(len(tcn_raw),len(dm))
    i30=min(6,n-1)  # index 6 = 30min (6*5min)
    ps30=(dm[i30]-G0)/30.0 if n>6 else 0
    ts30=(tcn_raw[i30]-G0)/30.0 if n>6 else 0
    tr30=tcn_raw[i30]-G0 if n>6 else 0
    dm30=dm[i30]-G0 if n>6 else 0
    ov=False; dm_w=0.75  # default DM blend weight

    if iob>1.0 and c2h<30: ov=True
    elif iob>0.5 and ts30>0.01 and ps30<-0.01: ov=True
    elif c2h>=30 and ts30<0.02 and ps30<0: ov=True
    elif abs(tr30)>2.5: ov=True
    elif iob>1.0 and c2h>=30 and ts30>0.015: ov=True
    elif c2h>=30 and iob<0.5 and tr30>2.0: ov=True
    elif ps30*ts30<0 and abs(tr30)>=0.6: ov=True  # ★ R7: 方向相反+|Δ|>=0.6
    else: pass

    if ov:
        # ★ 陡降保护: DM 30min跌>4.0 → 降DM权重到50%
        if dm30 < -4.0 or dm30 > 4.0:
            dm_w = 0.50
        result=[max(1.0,min(30.0,G0+dm_w*(dm[i]-G0)+(1-dm_w)*(tcn_raw[i]-G0))) for i in range(n)]
        return result, f"GATE(dm{int(dm_w*100)}%)"
    return list(tcn_raw), "none"

SCENARIOS = [
    ("S1: 无输入",             9.20, [],                [],                0.0,  0,  "down","flat"),
    ("S2: 仅8U@30min",         9.27, [],                [(30.0,8.0)],      5.48, 0,  "down","down"),
    ("S3: 8U@30+80g@60",       9.27, [(60.0,80.0)],     [(30.0,8.0)],      5.48, 80, "up_first","flat"),
    ("S4: 仅80g@60min",        9.27, [(60.0,80.0)],     [],                0.0,  80, "up_first","up_moderate"),
    ("S5: 高血糖无输入",       15.0,  [],                [],                0.0,  0,  "down","down"),  # 高血糖应下降
    ("S6: 正常血糖无输入",     6.0,   [],                [],                0.0,  0,  "flat","flat"),
    ("S7: 大剂量12U@15min",    10.0,  [],                [(15.0,12.0)],     8.5,  0,  "down","down"),
    ("S8: 小碳水30g@30min",    6.5,   [(30.0,30.0)],     [],                0.0,  30, "up_first","up_moderate"),
    ("S9: 大餐100g@45min",     7.0,   [(45.0,100.0)],    [],                0.0,  100,"up_first","up_moderate"),
    ("S10: 餐前胰岛素+碳水",   8.0,   [(30.0,60.0)],     [(15.0,4.0)],      3.2,  60, "up_first","flat"),
    ("S11: 低血糖+碳水补救",   3.5,   [(5.0,15.0)],      [],                0.0,  15, "up","up"),
    ("S12: 远餐(早+午2-4h前)",9.0,   [(120.0,60.0),(240.0,80.0)], [],     0.0,  140,"flat_or_down","flat"),
]

def check(name, dm, tcn_gated, G0, exp_dm, exp_tcn):
    issues=[]
    dm15=dm[3]-G0 if len(dm)>3 else 0; dm30=dm[6]-G0 if len(dm)>6 else 0
    dmEnd=dm[-1]-G0; g30=tcn_gated[6]-G0 if len(tcn_gated)>6 else 0
    if exp_dm=="down":
        if dm15>0.3: issues.append(f"DM15m应降却升{dm15:+.1f}")
        if dmEnd>1.0: issues.append(f"DM终点偏离{dmEnd:+.1f}")
    elif exp_dm=="up":
        if dm15<-0.3: issues.append(f"DM15m应升却降{dm15:+.1f}")
    elif exp_dm=="up_first":
        if dm15<-1.0: issues.append(f"DM15m大跌{dm15:+.1f}")
    elif exp_dm=="flat":
        if abs(dm30)>1.5: issues.append(f"DM30m波动{dm30:+.1f}")
    elif exp_dm=="flat_or_down":
        if dm15<-2.0: issues.append(f"DM15m跌太多{dm15:+.1f}")
    if exp_tcn=="flat":
        if abs(g30)>2.5: issues.append(f"Gate30m波动{g30:+.1f}")
    elif exp_tcn=="down":
        if g30>1.0: issues.append(f"Gate30m仍升{g30:+.1f}")  # 高G0时+1.0≈持平
    elif exp_tcn=="up":
        if g30<-0.5: issues.append(f"Gate30m不升反降{g30:+.1f}")
    elif exp_tcn=="up_moderate":
        if g30<-0.7: issues.append(f"Gate应升却降{g30:+.1f}")  # -0.7内=噪声
        if g30>5.0: issues.append(f"Gate暴涨{g30:+.1f}")
    return issues

all_fail=[]; total=0; passed=0
for seed in [42, 123, 456, 789, 1024]:
    np.random.seed(seed)
    rp=0; rf=0
    for nm, G0, meals, ins, iob, c2h, eDM, eTCN in SCENARIOS:
        total+=1
        gh=np.full(288,G0)+np.random.randn(288)*0.05
        bh=np.zeros(288); ch=np.zeros(288)
        for T,u in ins:
            i=287-int(T/5)
            if 0<=i<288: bh[i]=u
        for T,c in meals:
            i=287-int(T/5)
            if 0<=i<288: ch[i]=c
        dm=dalla(G0,meals,ins)
        tcn_raw,(a,b,c,d),_=tcn(gh,bh,ch)
        tcn_gated,reason=gate(tcn_raw,dm,G0,iob,c2h)
        issues=check(nm,dm,tcn_gated,G0,eDM,eTCN)
        if issues:
            rf+=1; all_fail.append((seed,nm,issues,reason,c,tcn_raw[6]-G0,dm[6]-G0,tcn_gated[6]-G0))
        else: rp+=1; passed+=1
    status = f"seed={seed}: {rp}/{rp+rf} pass"
    if rf>0: status += f"  FAIL: {[f[1] for f in all_fail if f[0]==seed]}"
    print(status)

print(f"\n  TOTAL: {passed}/{total} PASS")
if all_fail:
    print(f"  Failures ({len(all_fail)}):")
    for seed,nm,issues,reason,c,t30,d30,g30 in all_fail:
        print(f"    [{seed}] {nm}: {issues[0]} (c={c:+.2f} T30={t30:+.1f} D30={d30:+.1f} G30={g30:+.1f} [{reason}])")
else:
    print("  ALL 60 TESTS PASSED!")
