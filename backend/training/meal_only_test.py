"""
诊断 "记录饮食无胰岛素 → DM先下降再上升" 问题
"""
import numpy as np
import onnxruntime as ort
sess = ort.InferenceSession(r'D:\tangdun\android\app\src\main\assets\model_curve_v2.onnx')

def dalla(G0, meals, ins, w=65, isf=1.5, fast=7.0, basal=8.0, sigma=0.0, act=0.5, H=180):
    """当前 App 参数 (v3.0.22)"""
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

# ═══════════════════════════════════════
# 诊断: 不同时机记录饮食 → DM 方向
# ═══════════════════════════════════════
print("="*70)
print("  诊断: 仅饮食无胰岛素 — DallaMan 初始方向")
print("="*70)

test_cases = [
    # (name, G0, meal_time_ago_min, meal_carbs_g, fasting)
    ("刚吃 80g, G=6.5 (饭前正常)", 6.5, 0, 80, 6.5),
    ("刚吃 80g, G=7.5 (饭前微高)", 7.5, 0, 80, 7.0),
    ("刚吃 80g, G=9.0 (饭前偏高)", 9.0, 0, 80, 7.0),
    ("15min前吃 80g, G=7.0", 7.0, 15, 80, 7.0),
    ("30min前吃 80g, G=7.5", 7.5, 30, 80, 7.0),
    ("60min前吃 80g, G=9.0", 9.0, 60, 80, 7.0),
    ("刚吃 50g, G=6.5 (小餐)", 6.5, 0, 50, 6.5),
    ("刚吃 120g, G=6.5 (大餐)", 6.5, 0, 120, 6.5),
    ("刚吃 30g, G=8.0 (零食+高血糖)", 8.0, 0, 30, 7.0),
]

for name, G0, tAgo, carbs, fasting in test_cases:
    meals = []
    if tAgo > 0:
        meals = [(tAgo, carbs)]
    else:
        meals = [(0.01, carbs)]  # t≈0 表示"刚刚吃"

    dm = dalla(G0, meals, [], fast=fasting)

    dm_0 = dm[0]
    dm_3 = dm[3]  # 15min
    dm_6 = dm[6]  # 30min
    dm_12 = dm[12] # 60min
    dm_end = dm[-1]

    d15 = dm_3 - G0
    d30 = dm_6 - G0
    direction = "↑上升" if d15 > 0.1 else ("↓下降" if d15 < -0.1 else "→持平")
    direction30 = "↑上升" if d30 > 0.1 else ("↓下降" if d30 < -0.1 else "→持平")

    # 诊断胃/肠初始状态
    isfF = 1.0
    kSt = 0.035
    kG = 0.050
    w = 65
    Vg = 65 * 1.6
    Vg18 = Vg * 18

    stomach0 = sum([c*1000*0.9*np.exp(-kSt*T) for T,c in meals])
    gut0 = sum([c*1000*0.9*kSt/(kG-kSt)*(np.exp(-kSt*T)-np.exp(-kG*T)) for T,c in meals]) if meals else 0

    Ra0 = kG * gut0 / Vg18
    Uii0 = 0.060 * (G0 - fasting)
    Gm = G0 * 18
    Uid0 = 2.5 * Gm / (100 + Gm) * w / Vg18
    EGP0 = 2.07 * w / Vg18
    dG0 = Ra0 + EGP0 - Uii0 - Uid0

    issue = ""
    if d15 < -0.1:
        issue = f"!! 15min先下降{d15:+.1f} (Ra={Ra0:.4f} Uii={Uii0:.4f} Uid={Uid0:.4f} dG={dG0:+.4f})"
    elif d30 < -0.1 and d15 > 0:
        issue = f"  30min转下降 (OK: 先升后降)"

    print(f"  {name}: {G0:.1f}→{dm_3:.1f}(15m,{d15:+.1f}{direction})→{dm_6:.1f}(30m,{d30:+.1f}{direction30})→{dm_12:.1f}(60m)→{dm_end:.1f}(180m)  {issue}")

print(f"\n{'='*70}")
print("  根因分析")
print("="*70)
print("""
当饮食在 t≈0 记录时:
  - stomach 满 (72000mg @80g), gut 空 (0mg)
  - Ra = kGut * gut / Vg18 = 0 (肠道还是空的!)
  - 胃排空需要时间: kStomach=0.035 → 半衰~20min
  - 在这 20min 内, Uii 和 Uid 持续清糖 → 血糖先降
  - 等到 gut 积累足够 → Ra > Uii+Uid → 血糖才开始升

这就是 "先下降再上升" 的根因:
  胃→肠→血的延迟导致碳水吸收滞后, 高血糖时Uii清糖快于碳水吸收

修复方案:
  A. 模拟"初始快速胃排空": 进餐后前15min使用更高kStomach
  B. 直接给 gut 一个初始值: 假设一部分碳水已经进入肠道
  C. 使用双相胃排空: 液体相(快)+固体相(慢)
""")
