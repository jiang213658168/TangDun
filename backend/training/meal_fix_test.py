"""
修复: 双相胃排空 — 进食后前15min 20%快速入肠
"""
import numpy as np
import onnxruntime as ort
sess = ort.InferenceSession(r'D:\tangdun\android\app\src\main\assets\model_curve_v2.onnx')

def dalla_fixed(G0, meals, ins, w=65, isf=1.5, fast=7.0, basal=8.0, sigma=0.0, act=0.5, H=180):
    """★ 双相胃排空修复"""
    isfF=max(0.3,min(3.0,1.5/max(0.5,min(6.0,isf))))
    kSt_base=max(0.025,min(0.045,0.035-isfF*0.004))
    VmxG=max(5.0,min(12.0,10.0-isfF*2.0)); VgPK=max(1.4,min(2.0,1.6+(w-65)*0.01))
    k1=max(0.040,min(0.090,0.060+act*0.030))
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

    # ★★★ 双相胃排空: 进食<15min前 → 20%直接入肠 ★★★
    stomach=0.0; gut=0.0
    RAPID_FRACTION = 0.20  # 20% 快速相
    RAPID_WINDOW = 15.0    # 15min 内算"刚吃"
    for T,c in meals:
        mg=c*1000*fC
        base_stomach = mg*np.exp(-kSt_base*T)
        if T < RAPID_WINDOW:
            # 双相: 20%已入肠 + 80%在胃
            rapid_gut = mg * RAPID_FRACTION * np.exp(-kG*T)  # 快速入肠的部分也在被吸收
            slow_stomach = mg * (1-RAPID_FRACTION) * np.exp(-kSt_base*T)  # 慢相仍在胃
            if abs(kG-kSt_base)>1e-6:
                slow_gut = mg*(1-RAPID_FRACTION)*kSt_base/(kG-kSt_base)*(np.exp(-kSt_base*T)-np.exp(-kG*T))
            else:
                slow_gut = mg*(1-RAPID_FRACTION)*kSt_base*T*np.exp(-kSt_base*T)
            stomach += slow_stomach
            gut += rapid_gut + slow_gut
        else:
            # >15min: 标准单相
            stomach += base_stomach
            if abs(kG-kSt_base)>1e-6:
                gut += mg*kSt_base/(kG-kSt_base)*(np.exp(-kSt_base*T)-np.exp(-kG*T))
            else:
                gut += mg*kSt_base*T*np.exp(-kSt_base*T)
    gut=min(gut,VmxG*w/kG)

    dt=5.0; n=H//5; curve=[]
    def d(s):
        G,I,X,XL,st,gu,sq1,sq2=s; Ra=kG*gu/Vg18; hS=XL/(1+XL)
        EGP=hB*(1-hS)*w/Vg18; Uii=k1*(G-Gb); Gm=G*18; Vm=Vm0+VmX*X
        Uid=Vm*Gm/(Km0+Gm)*w/Vg18; Ren=rCl*(G-rT)*18 if G>rT else 0.0
        dG=Ra+EGP-Uii-Uid-Ren; dI=ka2*sq2/Vi-ke*I+sigma*max(0.0,G-Gb)
        iDr=max(0.0,I-Ib)/Ib; dX=-kp3*X+kp3*iDr; dXL=-kp2*XL+kp2*iDr
        gR=kSt_base*st; gM=VmxG*w; dSt=-min(gR,gM); dGu=min(gR,gM)-kG*gu
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

# 原版 (对比用)
def dalla_old(G0, meals, ins, w=65, isf=1.5, fast=7.0, basal=8.0, sigma=0.0, act=0.5, H=180):
    """旧版: 无双相胃排空"""
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
    iob_in=sum(u*0.5**(T/55.0) for T,u in ins if T<240); ci=max(iob_in*15.0,5.0)
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

# ═══════════════════════════════════════
# 对比测试: 旧版 vs 修复版
# ═══════════════════════════════════════
print("="*70)
print("  双相胃排空修复: 旧版 vs 新版")
print("="*70)

test_cases = [
    ("刚吃80g G=6.5(正常)", 6.5, 0, 80, 6.5),
    ("刚吃80g G=7.5(微高)", 7.5, 0, 80, 7.0),
    ("刚吃80g G=9.0(偏高)", 9.0, 0, 80, 7.0),  # ← 原来先降
    ("刚吃50g G=7.0(小餐)", 7.0, 0, 50, 7.0),
    ("刚吃120g G=6.5(大餐)", 6.5, 0, 120, 6.5),
    ("刚吃30g G=8.5(零食+高)", 8.5, 0, 30, 7.0),  # ← 极端
    ("15min前80g G=7.0", 7.0, 15, 80, 7.0),
    ("30min前80g G=7.5", 7.5, 30, 80, 7.0),
    ("60min前80g G=9.0", 9.0, 60, 80, 7.0),
    ("刚吃+10min前胰岛素", 8.0, 0, 80, 7.0),  # S10 类
]

all_ok = True
for name, G0, tAgo, carbs, fasting in test_cases:
    meals = [(max(0.01, tAgo), carbs)]
    ins = []
    if "胰岛素" in name:
        ins = [(10.0, 4.0)]  # 10min前4U

    old = dalla_old(G0, meals, ins, fast=fasting)
    new = dalla_fixed(G0, meals, ins, fast=fasting)

    old_d15 = old[3]-G0
    new_d15 = new[3]-G0
    old_d30 = old[6]-G0
    new_d30 = new[6]-G0

    old_dir = "↓" if old_d15 < -0.1 else ("↑" if old_d15 > 0.1 else "→")
    new_dir = "↓" if new_d15 < -0.1 else ("↑" if new_d15 > 0.1 else "→")

    issue = ""
    if old_d15 < -0.1 and new_d15 < -0.1:
        issue = "!! BOTH先降 (修复不够)"
        all_ok = False
    elif old_d15 < -0.1 and new_d15 >= -0.1:
        issue = f"FIXED: 旧{old_d15:+.1f}{old_dir}→新{new_d15:+.1f}{new_dir}"
    elif old_d15 >= -0.1 and new_d15 < -0.1:
        issue = f"REGRESSION: 旧{old_d15:+.1f}→新{new_d15:+.1f}{new_dir}"
        all_ok = False

    print(f"  {name}: 旧 {G0:.1f}→{old[3]:.1f}({old_d15:+.1f}{old_dir})→{old[6]:.1f}({old_d30:+.1f}) | 新 {G0:.1f}→{new[3]:.1f}({new_d15:+.1f}{new_dir})→{new[6]:.1f}({new_d30:+.1f})  {issue}")

print(f"\n  {'ALL OK' if all_ok else 'SOME ISSUES'}")

# 对"刚吃80g G=9.0"做详细打印
print(f"\n{'='*70}")
print("  详细: 刚吃80g G=9.0(偏高) — 修复前后对比")
print(f"{'='*70}")
old = dalla_old(9.0, [(0.01, 80)], [], fast=7.0)
new = dalla_fixed(9.0, [(0.01, 80)], [], fast=7.0)
for i in range(0, 37, 2):
    t = i*5
    print(f"    t={t:3d}min: 旧={old[i]:.2f}  新={new[i]:.2f}  diff={new[i]-old[i]:+.2f}")
