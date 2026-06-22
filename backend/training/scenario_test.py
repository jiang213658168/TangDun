"""
糖盾 4场景完整模拟 — DallaMan v3.0.20 + TCN + 物理门控
诊断每个场景的问题 → 修复 → 验证
"""
import numpy as np
import onnxruntime as ort

sess = ort.InferenceSession(r'D:\tangdun\android\app\src\main\assets\model_curve_v2.onnx')

def extract_features(gh, idx, bh, ch):
    start = max(0, idx - 288)
    history = gh[start:idx]
    if len(history) < 10:
        return [0.0]*15
    mean = float(np.mean(history)); std = float(np.std(history, ddof=1)) if len(history)>1 else 1.0
    if std <= 0: std = 1.0
    return [
        (gh[idx]-mean)/std,                                                         # f1
        (gh[idx]-gh[idx-1])/std if idx>=1 else 0,                                   # f2
        (gh[idx]-gh[idx-3])/std if idx>=3 else 0,                                   # f3
        (gh[idx]-gh[idx-6])/std if idx>=6 else 0,                                   # f4
        (gh[idx]-gh[idx-12])/std if idx>=12 else 0,                                  # f5
        ((gh[idx]-gh[idx-6])/std)/30.0 if idx>=6 else 0,                             # f6
        ((gh[idx]-gh[idx-12])/std)/60.0 if idx>=12 else 0,                           # f7
        (np.mean(history[-72:])-mean)/std if len(history)>=72 else 0,               # f8
        np.std(history[-72:],ddof=1)/std if len(history)>=72 else 0.0,              # f9
        float(np.sum(bh[max(0,idx-48):idx+1]))/20.0,                                # f10
        min((idx-max(np.where(bh[max(0,idx-144):idx+1]>0)[0])*5 if np.any(bh[max(0,idx-144):idx+1]>0) else 288*5)/120.0,1.0), # f11
        float(np.sum(ch[max(0,idx-48):idx+1]))/100.0,                               # f12
        min((idx-max(np.where(ch[max(0,idx-144):idx+1]>0)[0])*5 if np.any(ch[max(0,idx-144):idx+1]>0) else 288*5)/120.0,1.0), # f13
        0.0, 0.0                                                                     # f14,f15
    ]

def tcn_predict(gh, bh, ch, npts=36):
    idx = len(gh)-1
    feats = extract_features(gh, idx, bh, ch)
    x = np.array([feats], dtype=np.float32)
    out = sess.run(None, {'input': x})[0]
    a,b,c,d = out[0]
    G0 = gh[idx]
    curve = [G0*(1 + a*(i/(npts-1))**3 + b*(i/(npts-1))**2 + c*(i/(npts-1)) + d) for i in range(npts)]
    offset = curve[0] - G0
    return [v-offset for v in curve], (a,b,c,d), feats

# ═══════════════════════════════════════════════════════════════
# DallaMan RK4 — 完全对齐 App 端 DallaManModel.kt
# ═══════════════════════════════════════════════════════════════

def dalla_predict(G0, meals, insulins, weight=65, isf=1.5, fasting=7.0, basal=8.0,
                  sigma=0.0, activity=0.5, horizon=180):
    """与 App DallaManModel.predict() + Parameters.forUser() 完全一致"""
    isfF = max(0.3, min(3.0, 1.5/max(0.5, min(6.0, isf))))

    kStomach  = max(0.030, min(0.055, 0.050 - isfF*0.005))
    VmaxGastric = max(5.0, min(12.0, 10.0 - isfF*2.0))
    VgPerKg = max(1.4, min(2.0, 1.6+(weight-65)*0.01))
    k1 = max(0.040, min(0.090, 0.060+activity*0.030))
    Vm0 = max(1.5, min(4.0, 2.5-isfF*0.2+activity*0.3))
    VmX = max(0.02, min(0.10, 0.05-isfF*0.01))
    Km0 = 100.0
    hepaticBase = max(1.5, min(3.0, 2.07+isfF*0.4))
    renalThreshold = max(8.0, min(12.0, 8.0+fasting*0.3))
    kp3 = max(0.020, min(0.050, 0.045-isfF*0.007))
    kp2 = max(0.035, min(0.065, 0.060-isfF*0.007))
    ka1=0.018; ka2=0.018; ke=0.138; kG=0.065; rCl=0.005; fC=0.9
    Gb=fasting; Ib=basal

    Vg = max(60.0, min(300.0, weight*VgPerKg))
    Vi = max(2.0, min(25.0, weight*0.05))
    Vg18 = Vg*18.0

    G=G0; subQ1=0.0; subQ2=0.0
    for T,u in insulins:
        mU=u*100.0; subQ1+=mU*np.exp(-ka1*T)
        subQ2+=mU*ka1*T*np.exp(-ka1*T)

    Gexc=max(0.0,G-Gb)
    insF=ka2*subQ2/(ke*Vi)
    endo=sigma*Gexc/ke
    I=basal+insF+endo; I=max(basal*0.5,min(basal*8.0,I))
    iob_in=sum(u*0.5**(T/55.0) for T,u in insulins if T<240)
    ci=max(iob_in*15.0,5.0)
    if ci>I*1.5: I=min(ci,basal*8.0)

    X=max(0.0,min(5.0,(I-basal)/basal)); X_L=X

    stomach=0.0; gut=0.0
    for T,c in meals:
        mg=c*1000*fC; stomach+=mg*np.exp(-kStomach*T)
        if abs(kG-kStomach)>1e-6:
            gut+=mg*kStomach/(kG-kStomach)*(np.exp(-kStomach*T)-np.exp(-kG*T))
        else: gut+=mg*kStomach*T*np.exp(-kStomach*T)
    gut=min(gut,VmaxGastric*weight/kG)

    dt=5.0; n=horizon//5; curve=[]
    def d(s):
        G,I,X,XL,st,gu,sq1,sq2=s
        Ra=kG*gu/Vg18
        hS=XL/(1+XL); EGP=hepaticBase*(1-hS)*weight/Vg18
        Uii=k1*(G-Gb)
        Gm=G*18; Vm=Vm0+VmX*X; Uid=Vm*Gm/(Km0+Gm)*weight/Vg18
        Ren=rCl*(G-renalThreshold)*18 if G>renalThreshold else 0.0
        dG=Ra+EGP-Uii-Uid-Ren
        dI=ka2*sq2/Vi - ke*I + sigma*max(0.0,G-Gb)
        iDr=max(0.0,I-Ib)/Ib
        dX=-kp3*X+kp3*iDr; dXL=-kp2*XL+kp2*iDr
        gR=kStomach*st; gM=VmaxGastric*weight
        dSt=-min(gR,gM); dGu=min(gR,gM)-kG*gu
        dsq1=-ka1*sq1; dsq2=ka1*sq1-ka2*sq2
        return np.array([dG,dI,dX,dXL,dSt,dGu,dsq1,dsq2])

    for step in range(n+1):
        curve.append(max(1.0,min(30.0,G)))
        if step==n: break
        s=np.array([G,I,X,X_L,stomach,gut,subQ1,subQ2])
        k1v=d(s); s2=s+0.5*dt*k1v; k2v=d(s2)
        s3=s+0.5*dt*k2v; k3v=d(s3)
        s4=s+dt*k3v; k4v=d(s4)
        G=max(1.5,min(28.0,s[0]+dt/6*(k1v[0]+2*k2v[0]+2*k3v[0]+k4v[0])))
        I=max(0.5,min(basal*8.0,s[1]+dt/6*(k1v[1]+2*k2v[1]+2*k3v[1]+k4v[1])))
        X=max(0.0,min(5.0,s[2]+dt/6*(k1v[2]+2*k2v[2]+2*k3v[2]+k4v[2])))
        X_L=max(0.0,min(5.0,s[3]+dt/6*(k1v[3]+2*k3v[3]+2*k3v[3]+k4v[3])))
        stomach=max(0.0,s[4]+dt/6*(k1v[4]+2*k2v[4]+2*k3v[4]+k4v[4]))
        gut=max(0.0,s[5]+dt/6*(k1v[5]+2*k2v[5]+2*k3v[5]+k4v[5]))
        subQ1=max(0.0,s[6]+dt/6*(k1v[6]+2*k2v[6]+2*k3v[6]+k4v[6]))
        subQ2=max(0.0,s[7]+dt/6*(k1v[7]+2*k2v[7]+2*k3v[7]+k4v[7]))
    return curve

# ═══════════════════════════════════════════════════════════════
# 场景测试
# ═══════════════════════════════════════════════════════════════
np.random.seed(42)

scenarios = [
    {"name":"S1: none",          "G0":9.20, "meals":[],                  "insulins":[],                "iob":0.0,  "c2h":0},
    {"name":"S2: insulin only",  "G0":9.27, "meals":[],                  "insulins":[(30.0,8.0)],       "iob":5.48, "c2h":0},
    {"name":"S3: insulin+carbs", "G0":9.27, "meals":[(60.0,80.0)],       "insulins":[(30.0,8.0)],       "iob":5.48, "c2h":80},
    {"name":"S4: carbs only",    "G0":9.27, "meals":[(60.0,80.0)],       "insulins":[],                 "iob":0.0,  "c2h":80},
]

for sc in scenarios:
    G0=sc['G0']; meals=sc['meals']; ins=sc['insulins']; iob=sc['iob']; c2h=sc['c2h']

    # 构造288点历史
    gh=np.full(288,G0)+np.random.randn(288)*0.05
    bh=np.zeros(288); ch=np.zeros(288)
    for T,u in ins:
        i=287-int(T/5)
        if 0<=i<288: bh[i]=u
    for T,c in meals:
        i=287-int(T/5)
        if 0<=i<288: ch[i]=c

    # DallaMan
    dm = dalla_predict(G0, meals, ins)
    # TCN
    tcn_raw, (a,b,c,d), feats = tcn_predict(gh, bh, ch)

    # 物理门控 (与 App 一致)
    h6=min(5,len(dm)-1)
    ps=(dm[h6]-G0)/30.0 if h6>0 else 0
    ts=(tcn_raw[min(5,len(tcn_raw)-1)]-G0)/30.0
    override = (iob>1.0 and c2h<30) or (iob>2.0 and ts>0.02 and ps<-0.02) or (c2h>=30 and ts<0.02)
    if override:
        tcn_gated = [max(1.0,min(30.0,G0+ps*0.6*i*5.0)) for i in range(len(tcn_raw))]
    else:
        tcn_gated = list(tcn_raw)

    print(f"\n{'='*60}")
    print(f"  {sc['name']}  G0={G0}  IOB={iob:.1f}  carb2h={c2h}g")
    print(f"  meals={meals}  insulins={ins}")
    print(f"{'='*60}")
    print(f"  DallaMan: t=0:{dm[0]:.1f}  30min:{dm[6]:.1f}({dm[6]-G0:+.1f})  60min:{dm[12]:.1f}({dm[12]-G0:+.1f})  120min:{dm[24]:.1f}  180min:{dm[-1]:.1f}")
    print(f"  TCN raw:  t=0:{tcn_raw[0]:.1f}  30min:{tcn_raw[6]:.1f}({tcn_raw[6]-G0:+.1f})  60min:{tcn_raw[12]:.1f}  120min:{tcn_raw[24]:.1f}")
    print(f"  TCN params: a={a:+.3f} b={b:+.3f} c={c:+.3f} d={d:+.3f}")
    print(f"  Features: f10(ins)={feats[9]:.3f} f11(tIns)={feats[10]:.3f} f12(carb)={feats[11]:.3f} f13(tCarb)={feats[12]:.3f}")
    print(f"  physioSlope30={ps:+.4f}  tcnSlope30={ts:+.4f}  override={override}")
    if override:
        print(f"  TCN gated: t=0:{tcn_gated[0]:.1f}  30min:{tcn_gated[6]:.1f}({tcn_gated[6]-G0:+.1f})")

    # 问题标记
    issues = []
    # S1: 无输入 → 应该向基线回归
    if sc['name']=='S1: none':
        if tcn_raw[6] > G0+1.0: issues.append("TCN暴涨(无输入不应大涨)")
        if dm[6] < G0-2.0: issues.append("DallaMan跌太快(30min跌>2)")
    # S2: 仅胰岛素 → 应该下降
    if sc['name']=='S2: insulin only':
        if tcn_raw[6] > G0: issues.append("TCN上升(有胰岛素应下降)")
        if dm[6] > G0: issues.append("DallaMan上升(有胰岛素应下降)")
    # S3: 胰岛素+碳水 → 应该先微升再降(胰岛素高峰在+30~60min)
    if sc['name']=='S3: insulin+carbs':
        if tcn_raw[6] < G0-1: issues.append("TCN急降(有碳水不该急降)")
        # 期望 DallaMan: 碳水仍在吸收 → 短期平稳或微升, 然后胰岛素高峰→下降
        if dm[3] < G0-0.5: issues.append("DallaMan t=15min就降(碳水还在吸收)")
    # S4: 仅碳水 → 应该上升后回落
    if sc['name']=='S4: carbs only':
        if tcn_raw[6] > G0+5: issues.append("TCN暴涨(仅碳水不应涨5+)")
        if dm[6] < G0-0.1: issues.append("DallaMan下降(有碳水应上升)")

    if issues:
        for x in issues: print(f"  !! {x}")
    else:
        print(f"  OK")
