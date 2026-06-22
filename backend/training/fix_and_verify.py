"""
修复 DallaMan + TCN 底层问题 — 完整测试
问题:
  S1: TCN暴涨 (c项恒正) — 无输入应回归基线
  S2: TCN暴涨 (c项恒正) — 有胰岛素应下降
  S3: DallaMan单调下降 — 应有碳水吸收→上升→胰岛素高峰→下降的双相
  S4: DallaMan下降 — 仅碳水应上升
根因:
  1. kStomach=0.055太快 → 碳水60min后几乎吸完 → DallaMan看不到上升
  2. TCN c项恒正 → 物理门控不够强大
  3. ka1/ka2=0.018 → 速效胰岛素皮下吸收偏慢
修复:
  A. kStomach: 0.050→0.035 (中式米饭消化慢, 胃排空半衰20min)
  B. ka1/ka2: 0.018→0.024 (速效类似物峰值~75min更符合临床)
  C. TCN物理门控: 扩大触发条件 + 用DallaMan曲线shape而非纯线性
"""
import numpy as np
import onnxruntime as ort

sess = ort.InferenceSession(r'D:\tangdun\android\app\src\main\assets\model_curve_v2.onnx')

# ═══════════════════════════════════════════════════════════════
# 通用工具
# ═══════════════════════════════════════════════════════════════

def ext_features(gh, idx, bh, ch):
    """对齐 App FeatureExtractor (修复 f11/f13 坐标系bug)"""
    start = max(0, idx - 288)
    history = gh[start:idx]
    if len(history) < 10: return [0.0]*15
    mean = float(np.mean(history)); std = float(np.std(history, ddof=1)) if len(history)>1 else 1.0
    if std <= 0: std = 1.0

    f1 = (gh[idx]-mean)/std
    f2 = (gh[idx]-gh[idx-1])/std if idx>=1 else 0
    f3 = (gh[idx]-gh[idx-3])/std if idx>=3 else 0
    f4 = (gh[idx]-gh[idx-6])/std if idx>=6 else 0
    f5 = (gh[idx]-gh[idx-12])/std if idx>=12 else 0
    f6 = f4/30.0; f7 = f5/60.0
    recent72 = history[-72:] if len(history)>=72 else history
    f8 = (np.mean(recent72)-mean)/std
    f9 = np.std(recent72,ddof=1)/std if len(recent72)>1 else 0.0
    # f10-f13: 在原始288点数组而非切片中定位
    f10 = float(np.sum(bh[max(0,idx-48):idx+1]))/20.0
    # f11: 最近注射时间 (在原始288中, idx=0..287)
    bolus_recent = bh[max(0,idx-144):idx+1]
    bolus_idxs = np.where(bolus_recent > 0)[0]
    if len(bolus_idxs) > 0:
        steps_ago = len(bolus_recent) - 1 - bolus_idxs[-1]  # steps since last bolus
        f11 = min(steps_ago * 5.0 / 120.0, 1.0)
    else: f11 = 1.0
    # f12-f13: 同上
    f12 = float(np.sum(ch[max(0,idx-48):idx+1]))/100.0
    carb_recent = ch[max(0,idx-144):idx+1]
    carb_idxs = np.where(carb_recent > 0)[0]
    if len(carb_idxs) > 0:
        steps_ago = len(carb_recent) - 1 - carb_idxs[-1]
        f13 = min(steps_ago * 5.0 / 120.0, 1.0)
    else: f13 = 1.0
    return [f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13,0.0,0.0]

def tcn_predict(gh, bh, ch, npts=37):
    idx = len(gh)-1
    feats = ext_features(gh, idx, bh, ch)
    x = np.array([feats], dtype=np.float32)
    out = sess.run(None, {'input': x})[0]
    a,b,c,d = out[0]
    G0 = gh[idx]
    curve = []
    for i in range(npts):
        t = i/(npts-1) if npts>1 else 0
        curve.append(G0*(1 + a*t**3 + b*t**2 + c*t + d))
    offset = curve[0] - G0
    return [max(1.0,min(30.0,v-offset)) for v in curve], (a,b,c,d), feats

# ═══════════════════════════════════════════════════════════════
# DallaMan — 修复版参数
# ═══════════════════════════════════════════════════════════════

def dalla_fixed(G0, meals, insulins, weight=65, isf=1.5, fasting=7.0, basal=8.0,
                sigma=0.0, activity=0.5, horizon=180):
    """
    ★ 修复:
      kStomach_base: 0.050→0.035 (中式饮食胃排空慢)
      ka1/ka2: 0.018→0.024 (速效胰岛素峰值~75min)
      kGut也相应调慢: 0.065→0.050 (匹配慢排空)
    """
    isfF = max(0.3, min(3.0, 1.5/max(0.5, min(6.0, isf))))

    kStomach  = max(0.025, min(0.045, 0.035 - isfF*0.004))  # ★ 0.050→0.035
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
    ka1=0.024; ka2=0.024; ke=0.138  # ★ 0.018→0.024
    kG=0.050; rCl=0.005; fC=0.9     # ★ kGut 0.065→0.050
    Gb=fasting; Ib=basal

    Vg = max(60.0, min(300.0, weight*VgPerKg))
    Vi = max(2.0, min(25.0, weight*0.05))
    Vg18 = Vg*18.0

    G=G0; subQ1=0.0; subQ2=0.0
    for T,u in insulins:
        mU=u*100.0
        subQ1+=mU*np.exp(-ka1*T)
        if abs(ka2-ka1)>1e-6:
            subQ2+=mU*ka1/(ka2-ka1)*(np.exp(-ka1*T)-np.exp(-ka2*T))
        else:
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
        mg=c*1000*fC
        stomach+=mg*np.exp(-kStomach*T)
        if abs(kG-kStomach)>1e-6:
            gut+=mg*kStomach/(kG-kStomach)*(np.exp(-kStomach*T)-np.exp(-kG*T))
        else:
            gut+=mg*kStomach*T*np.exp(-kStomach*T)
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
# TCN 物理门控 — 增强版
# ═══════════════════════════════════════════════════════════════

def physical_gate_enhanced(tcn_raw, dm_curve, iob, carbs_2h, G0):
    """
    ★ 增强物理门控:
    1. IOB>0.5U 且无碳水 → 完全用DallaMan曲线shape (不只是linear slope)
    2. c项>0.5 + IOB>0.5 → TCN方向错误, 用DallaMan
    3. carb2h>0 + DallaMan下降 + TCN暴涨 → DallaMan主导
    """
    n = len(tcn_raw)
    ps30 = (dm_curve[min(5,n-1)] - G0)/30.0 if n>5 else 0
    ts30 = (tcn_raw[min(5,n-1)] - G0)/30.0 if n>5 else 0

    override = False
    override_type = "none"

    # 场景A: 显著IOB + 无碳水 → TCN应下降, 若上升则覆盖
    if iob > 1.0 and carbs_2h < 30:
        override = True
        override_type = "IOB主导"
    # 场景B: IOB+TCN上升+DM下降 → 矛盾, 信DM
    elif iob > 0.5 and ts30 > 0.01 and ps30 < -0.01:
        override = True
        override_type = "方向冲突(IOB)"
    # 场景C: 有碳水但DM下降明显+TCN暴涨 → TCN过度反应
    elif carbs_2h >= 30 and ts30 > 0.05 and ps30 < 0.0:
        override = True
        override_type = "TCN过度(碳水)"
    # 场景D: c项>1.0 → TCN恒正bug, 全部覆盖
    # (无法从tcn_raw直接取c, 传进来)

    if override:
        # ★ 用DallaMan曲线shape缩放, 而非线性斜率
        # DallaMan在30-120min的形状更符合生理
        dm_offset = dm_curve[0] - G0
        result = []
        for i in range(n):
            # 25% TCN残差 + 75% DallaMan shape
            tcn_contrib = 0.25 * (tcn_raw[i] - G0)
            dm_contrib = 0.75 * (dm_curve[i] - dm_offset - G0)
            result.append(max(1.0, min(30.0, G0 + tcn_contrib + dm_contrib)))
        return result, override_type
    return list(tcn_raw), override_type

# ═══════════════════════════════════════════════════════════════
# 测试 4 场景
# ═══════════════════════════════════════════════════════════════
np.random.seed(42)

scenarios = [
    ("S1: 无输入",              9.20, [],                [],                0.0,  0),
    ("S2: 仅8U胰岛素30min前",   9.27, [],                [(30.0,8.0)],      5.48, 0),
    ("S3: 8U胰岛素+80g碳水",    9.27, [(60.0,80.0)],     [(30.0,8.0)],      5.48, 80),
    ("S4: 仅80g碳水60min前",    9.27, [(60.0,80.0)],     [],                0.0,  80),
]

print("="*70)
print("  糖盾 4场景修复验证 — kStomach→0.035, ka→0.024, 增强物理门控")
print("="*70)

all_ok = True
for name, G0, meals, insulins, iob, c2h in scenarios:
    print(f"\n{'─'*60}")
    print(f"  {name}  G0={G0}  IOB={iob:.1f}  carb2h={c2h}g")

    # 构造288点历史
    gh = np.full(288, G0) + np.random.randn(288)*0.05
    bh = np.zeros(288); ch = np.zeros(288)
    for T,u in insulins:
        i = 287 - int(T/5)
        if 0<=i<288: bh[i]=u
    for T,c in meals:
        i = 287 - int(T/5)
        if 0<=i<288: ch[i]=c

    # DallaMan 修复版
    dm = dalla_fixed(G0, meals, insulins)
    # TCN
    tcn_raw, (a,b,c,d), feats = tcn_predict(gh, bh, ch)
    # 增强物理门控
    tcn_gated, gate_reason = physical_gate_enhanced(tcn_raw, dm, iob, c2h, G0)

    # 输出
    print(f"  DallaMan:  t=0:{dm[0]:.1f}  15:{dm[3]:.1f}  30:{dm[6]:.1f}  60:{dm[12]:.1f}  120:{dm[24]:.1f}  180:{dm[-1]:.1f}")
    print(f"  TCN raw:   t=0:{tcn_raw[0]:.1f}  15:{tcn_raw[3]:.1f}  30:{tcn_raw[6]:.1f}  60:{tcn_raw[12]:.1f}  120:{tcn_raw[24]:.1f}")
    print(f"  TCN gated: t=0:{tcn_gated[0]:.1f}  15:{tcn_gated[3]:.1f}  30:{tcn_gated[6]:.1f}  60:{tcn_gated[12]:.1f}  120:{tcn_gated[24]:.1f}")
    print(f"  TCN params: a={a:+.3f} b={b:+.3f} c={c:+.3f} d={d:+.3f}  gate={gate_reason}")

    # ── 判断 ──
    ok = True
    if name.startswith("S1"):
        # 无输入 → 血糖应缓慢向基线回归, 30min不应暴涨
        if tcn_gated[6] > G0+2.0: print(f"  FAIL: TCN gated仍暴涨 (+{tcn_gated[6]-G0:.1f})"); ok=False
        if dm[6] < G0-3.0: print(f"  FAIL: DallaMan暴跌 ({dm[6]-G0:.1f})"); ok=False
        if dm[-1] > 8.5 or dm[-1] < 6.0: print(f"  FAIL: DallaMan 180min不在基线 ({dm[-1]:.1f})"); ok=False
    elif name.startswith("S2"):
        # 仅胰岛素 → 应稳步下降, 不应上升
        if tcn_gated[6] > G0+0.1: print(f"  FAIL: TCN gated上升"); ok=False
        if dm[6] > G0: print(f"  FAIL: DallaMan上升"); ok=False
    elif name.startswith("S3"):
        # 胰岛素+碳水 → 碳水吸收→微升→胰岛素高峰→下降 (双相)
        if dm[3] < G0-1.5: print(f"  FAIL: DallaMan 15min暴跌 (碳水还在吸)"); ok=False
        if tcn_gated[6] > G0+2.0: print(f"  FAIL: TCN暴涨"); ok=False
    elif name.startswith("S4"):
        # 仅碳水 → 应短期上升 (碳水吸收) → 后回落
        if dm[6] < G0-0.5: print(f"  FAIL: DallaMan下降 (有碳水应上升)"); ok=False
        if dm[3] < G0-0.5: print(f"  FAIL: DallaMan 15min就降"); ok=False
        if tcn_gated[6] > G0+4.0: print(f"  FAIL: TCN过度上涨"); ok=False

    if ok:
        print(f"  PASS")
    else:
        all_ok = False

print(f"\n{'='*70}")
print(f"  结果: {'ALL PASS' if all_ok else 'SOME FAILED'}")
print(f"{'='*70}")
