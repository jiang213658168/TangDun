# backend/training/train_curve_v2.py
# 血糖曲线预测 - 从TCN开始，逐步优化
#
# 数据集: OhioT1DM + HUPA（真实患者数据）
# 特征: 只用数据集中的字段
# 模型: TCN（从简单开始）
# 输出: 曲线参数，可生成任意时间点的血糖值

import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader, Dataset
import numpy as np
import pandas as pd
import json
import os
import time

import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# ==================== 数据加载 ====================

def load_data(data_path):
    """加载OhioT1DM + HUPA数据"""
    print("加载数据...")
    df = pd.read_csv(data_path)
    print(f"  总记录: {len(df):,}")
    print(f"  患者数: {df['patient_id'].nunique()}")
    print(f"  字段: {list(df.columns)}")
    return df


def split_patients(df, train_ratio=0.7, val_ratio=0.15):
    """按患者分割数据集（避免数据泄漏）"""
    patients = df['patient_id'].unique()
    np.random.seed(42)
    np.random.shuffle(patients)

    n_train = int(len(patients) * train_ratio)
    n_val = int(len(patients) * val_ratio)

    train_pids = set(patients[:n_train])
    val_pids = set(patients[n_train:n_train + n_val])
    test_pids = set(patients[n_train + n_val:])

    print(f"训练集: {len(train_pids)}患者")
    print(f"验证集: {len(val_pids)}患者")
    print(f"测试集: {len(test_pids)}患者")

    return train_pids, val_pids, test_pids


# ==================== 特征提取 ====================

def extract_features_for_patient(pdata, idx):
    """提取单个时间点的特征（使用数据集所有字段）

    数据集字段:
    - glucose: CGM血糖值
    - carbs: 碳水摄入
    - bolus: 胰岛素大剂量
    - basal_rate: 基础胰岛素率
    - heart_rate: 心率
    - steps: 步数

    特征（15维）:
    1-9: 血糖动态特征
    10-11: 胰岛素特征
    12-13: 碳水特征
    14: 心率特征
    15: 步数特征
    """
    glucose_values = pdata['glucose'].values

    # 计算统计量（只用历史数据，避免泄露）
    start = max(0, idx - 288)  # 24小时
    history = glucose_values[start:idx]

    if len(history) < 10:
        return None

    mean = np.mean(history)
    std = np.std(history) if np.std(history) > 0 else 1.0

    # === 血糖特征 (1-9) ===
    # 1: 当前血糖值（归一化）
    f1 = (glucose_values[idx] - mean) / std

    # 2-5: 变化量
    f2 = (glucose_values[idx] - glucose_values[idx-1]) / std if idx >= 1 else 0  # 5min
    f3 = (glucose_values[idx] - glucose_values[idx-3]) / std if idx >= 3 else 0  # 15min
    f4 = (glucose_values[idx] - glucose_values[idx-6]) / std if idx >= 6 else 0  # 30min
    f5 = (glucose_values[idx] - glucose_values[idx-12]) / std if idx >= 12 else 0  # 1h

    # 6-7: ROC
    f6 = f4 / 30.0  # 30min ROC
    f7 = f5 / 60.0  # 1h ROC

    # 8-9: 统计特征
    recent = history[-72:] if len(history) >= 72 else history  # 6h
    f8 = (np.mean(recent) - mean) / std
    f9 = np.std(recent) / std

    # === 胰岛素特征 (10-11) ===
    basal_values = pdata['basal_rate'].values if 'basal_rate' in pdata.columns else np.zeros(len(glucose_values))
    bolus_values = pdata['bolus'].values if 'bolus' in pdata.columns else np.zeros(len(glucose_values))

    # 10: 最近4小时胰岛素总量
    insulin_4h = bolus_values[max(0, idx-48):idx+1]
    f10 = np.sum(insulin_4h)

    # 11: 最近注射时间（分钟）
    nonzero = np.nonzero(bolus_values[max(0, idx-144):idx+1])[0]
    f11 = (len(bolus_values[max(0, idx-144):idx+1]) - nonzero[-1]) * 5 if len(nonzero) > 0 else 999

    # === 碳水特征 (12-13) ===
    carb_values = pdata['carbs'].values if 'carbs' in pdata.columns else np.zeros(len(glucose_values))

    # 12: 最近4小时碳水总量
    carb_4h = carb_values[max(0, idx-48):idx+1]
    f12 = np.sum(carb_4h)

    # 13: 最近进食时间（分钟）
    nonzero = np.nonzero(carb_values[max(0, idx-144):idx+1])[0]
    f13 = (len(carb_values[max(0, idx-144):idx+1]) - nonzero[-1]) * 5 if len(nonzero) > 0 else 999

    # === 心率特征 (14) ===
    hr_values = pdata['heart_rate'].values if 'heart_rate' in pdata.columns else np.zeros(len(glucose_values))
    hr_recent = hr_values[max(0, idx-12):idx+1]
    hr_valid = hr_recent[hr_recent > 0]
    f14 = np.mean(hr_valid) if len(hr_valid) > 0 else 0.0

    # === 步数特征 (15) ===
    step_values = pdata['steps'].values if 'steps' in pdata.columns else np.zeros(len(glucose_values))
    steps_1h = step_values[max(0, idx-12):idx+1]
    f15 = np.sum(steps_1h)

    return np.array([f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15], dtype=np.float32)


# ==================== 数据集 ====================

class GlucoseDataset(Dataset):
    """血糖曲线数据集"""

    def __init__(self, df, patient_ids, horizon_minutes=60, max_samples_per_patient=2000):
        self.features = []
        self.targets = []
        self.anchors = []

        horizon_steps = horizon_minutes // 5  # 5分钟一个点

        print(f"  提取特征...")
        total_patients = len(patient_ids)
        processed = 0

        for pid in patient_ids:
            pdata = df[df['patient_id'] == pid].reset_index(drop=True)
            glucose_values = pdata['glucose'].values

            if len(glucose_values) < 300:  # 至少24小时数据
                continue

            # 采样（避免数据太多）
            n_samples = min(len(glucose_values) - 288 - horizon_steps, max_samples_per_patient)
            if n_samples <= 0:
                continue

            indices = np.linspace(288, len(glucose_values) - horizon_steps - 1, n_samples, dtype=int)

            for i in indices:
                feat = extract_features_for_patient(pdata, i)
                if feat is None or np.any(np.isnan(feat)):
                    continue

                # 目标：未来曲线参数
                future = glucose_values[i:i + horizon_steps]
                current = glucose_values[i]

                # 用多项式拟合曲线
                t = np.linspace(0, 1, horizon_steps)
                normalized_future = (future - current) / current

                try:
                    coeffs = np.polyfit(t, normalized_future, 3)
                    params = coeffs.tolist()
                except:
                    params = [0, 0, 0, 0]

                if np.any(np.isnan(params)):
                    continue

                self.features.append(feat)
                self.targets.append(params)
                self.anchors.append(current)

            processed += 1
            if processed % 10 == 0 or processed == total_patients:
                print(f"    {processed}/{total_patients} 患者, 样本: {len(self.features)}")

        self.features = np.array(self.features, dtype=np.float32)
        self.targets = np.array(self.targets, dtype=np.float32)
        self.anchors = np.array(self.anchors, dtype=np.float32)

        print(f"  完成! 样本数: {len(self.features)}")

    def __len__(self):
        return len(self.features)

    def __getitem__(self, idx):
        return torch.FloatTensor(self.features[idx]), torch.FloatTensor(self.targets[idx])


# ==================== TCN模型 ====================

class CausalConv1d(nn.Module):
    """因果卷积"""
    def __init__(self, in_ch, out_ch, kernel, dilation=1):
        super().__init__()
        self.padding = (kernel - 1) * dilation
        self.conv = nn.Conv1d(in_ch, out_ch, kernel, dilation=dilation, padding=self.padding)

    def forward(self, x):
        return self.conv(x)[:, :, :x.size(2)]


class TemporalBlock(nn.Module):
    """时序块"""
    def __init__(self, n_in, n_out, kernel, dilation, dropout=0.2):
        super().__init__()
        self.conv1 = CausalConv1d(n_in, n_out, kernel, dilation)
        self.bn1 = nn.BatchNorm1d(n_out)
        self.conv2 = CausalConv1d(n_out, n_out, kernel, dilation)
        self.bn2 = nn.BatchNorm1d(n_out)
        self.dropout = nn.Dropout(dropout)
        self.relu = nn.ReLU()
        self.downsample = nn.Conv1d(n_in, n_out, 1) if n_in != n_out else None

    def forward(self, x):
        out = self.relu(self.bn1(self.conv1(x)))
        out = self.dropout(out)
        out = self.relu(self.bn2(self.conv2(out)))
        out = self.dropout(out)
        res = x if self.downsample is None else self.downsample(x)
        return self.relu(out + res)


class TCNCurvePredictor(nn.Module):
    """TCN曲线预测模型"""

    def __init__(self, input_dim=15, channels=[64, 64, 128], dropout=0.2):
        super().__init__()

        # 输入嵌入
        self.input_proj = nn.Sequential(
            nn.Linear(input_dim, channels[0]),
            nn.LayerNorm(channels[0]),
            nn.GELU(),
        )

        # TCN
        layers = []
        for i in range(len(channels)):
            in_ch = channels[0] if i == 0 else channels[i-1]
            layers.append(TemporalBlock(in_ch, channels[i], 3, 2**i, dropout))
        self.tcn = nn.Sequential(*layers)

        # 输出头
        self.head = nn.Sequential(
            nn.AdaptiveAvgPool1d(1),
            nn.Flatten(),
            nn.Linear(channels[-1], 64),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(64, 4),  # 4个曲线参数
        )

    def forward(self, x):
        if x.dim() == 2:
            x = x.unsqueeze(1)
        embedded = self.input_proj(x)
        tcn_out = self.tcn(embedded.permute(0, 2, 1))
        return self.head(tcn_out)


# ==================== 损失函数 ====================

class CurveLoss(nn.Module):
    """曲线损失函数（参数损失 + 形状损失）"""

    def __init__(self, alpha=0.7, beta=0.3):
        super().__init__()
        self.alpha = alpha
        self.beta = beta
        self.huber = nn.HuberLoss(delta=1.0)

    def forward(self, pred_params, actual_params, anchors):
        # 参数损失
        param_loss = self.huber(pred_params, actual_params)

        # 曲线形状损失
        batch_size = pred_params.shape[0]
        shape_loss = 0.0
        for i in range(min(batch_size, 32)):  # 限制batch避免OOM
            pred_curve = generate_curve(
                pred_params[i].detach().cpu().numpy(),
                anchors[i].detach().cpu().numpy()
            )
            actual_curve = generate_curve(
                actual_params[i].detach().cpu().numpy(),
                anchors[i].detach().cpu().numpy()
            )
            shape_loss += np.mean((pred_curve - actual_curve) ** 2)
        shape_loss = shape_loss / min(batch_size, 32)

        return self.alpha * param_loss + self.beta * shape_loss


def generate_curve(params, current_value, num_points=25):
    """用参数生成曲线（25个点，包含0-120分钟）"""
    t = np.linspace(0, 1, num_points)
    a, b, c, d = params
    relative_change = a * t**3 + b * t**2 + c * t + d
    return current_value * (1 + relative_change)


# ==================== 评估 ====================

@torch.no_grad()
def evaluate(model, loader, device, anchors):
    """评估模型（含关键时间点）"""
    model.eval()
    all_params = []
    all_targets = []

    for bx, by in loader:
        pred = model(bx.to(device))
        all_params.extend(pred.cpu().numpy())
        all_targets.extend(by.numpy())

    pred_params = np.array(all_params)
    target_params = np.array(all_targets)

    # 关键时间点
    key_points = {'5min': 1, '15min': 3, '30min': 6, '60min': 12, '120min': 24}

    mae_list = []
    key_maes = {k: [] for k in key_points}
    clarke_a_count = 0
    total_count = 0

    for i in range(min(500, len(pred_params))):
        pred_curve = generate_curve(pred_params[i], anchors[i])
        target_curve = generate_curve(target_params[i], anchors[i])

        mae = np.mean(np.abs(pred_curve - target_curve))
        mae_list.append(mae)

        # 关键时间点MAE
        for name, idx in key_points.items():
            if idx < len(pred_curve):
                key_maes[name].append(abs(pred_curve[idx] - target_curve[idx]))

        # Clarke A区
        for p, t in zip(pred_curve, target_curve):
            if (t < 5.6 and abs(p - t) <= 1.4) or (t >= 5.6 and 0.8 * t <= p <= 1.2 * t):
                clarke_a_count += 1
            total_count += 1

    avg_mae = np.mean(mae_list)
    clarke_a = clarke_a_count / total_count * 100 if total_count > 0 else 0
    key_maes_final = {k: np.mean(v) if v else 0 for k, v in key_maes.items()}

    return {
        'mae': avg_mae,
        'clarke_a': clarke_a,
        'key_maes': key_maes_final,
        'n': len(pred_params),
    }


# ==================== 训练 ====================

def train_epoch(model, loader, optimizer, criterion, device, scaler, anchors):
    """训练一个epoch"""
    model.train()
    total_loss = 0
    n = 0

    for batch_idx, (bx, by) in enumerate(loader):
        bx = bx.to(device, non_blocking=True)
        by = by.to(device, non_blocking=True)
        batch_anchors = anchors[batch_idx * len(bx):(batch_idx + 1) * len(bx)].to(device)

        with torch.amp.autocast('cuda'):
            pred = model(bx)
            loss = criterion(pred, by, batch_anchors)

        optimizer.zero_grad(set_to_none=True)
        scaler.scale(loss).backward()
        scaler.unscale_(optimizer)
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        scaler.step(optimizer)
        scaler.update()

        total_loss += loss.item()
        n += 1

    return total_loss / n


# ==================== 主函数 ====================

def main():
    data_dir = os.path.join(os.path.dirname(__file__), 'data')
    data_path = os.path.join(data_dir, 'ohio_hupa_data.csv')

    print("=" * 60)
    print("血糖曲线预测 - TCN模型")
    print("=" * 60)

    if not os.path.exists(data_path):
        print(f"错误: 数据文件不存在: {data_path}")
        return

    # 加载数据
    df = load_data(data_path)

    # 分割数据集
    train_pids, val_pids, test_pids = split_patients(df)

    # GPU
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    print(f"\n设备: {device}")

    # 创建数据集
    print("\n加载训练集...")
    train_dataset = GlucoseDataset(df, train_pids, horizon_minutes=60)

    print("加载验证集...")
    val_dataset = GlucoseDataset(df, val_pids, horizon_minutes=60)

    print("加载测试集...")
    test_dataset = GlucoseDataset(df, test_pids, horizon_minutes=60)

    # 数据加载器
    train_loader = DataLoader(
        train_dataset, batch_size=512, shuffle=True,
        num_workers=4, pin_memory=True, persistent_workers=True
    )
    val_loader = DataLoader(
        val_dataset, batch_size=512,
        num_workers=4, pin_memory=True, persistent_workers=True
    )
    test_loader = DataLoader(
        test_dataset, batch_size=512,
        num_workers=4, pin_memory=True, persistent_workers=True
    )

    # 创建模型
    model = TCNCurvePredictor(
        input_dim=train_dataset.features.shape[1],
        channels=[64, 64, 128],
        dropout=0.2,
    ).to(device)

    param_count = sum(p.numel() for p in model.parameters())
    print(f"\n模型: TCN")
    print(f"参数量: {param_count:,}")
    print(f"特征维度: {train_dataset.features.shape[1]}")

    # 训练配置
    criterion = CurveLoss()
    optimizer = optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingWarmRestarts(optimizer, T_0=10, T_mult=2)
    scaler = torch.amp.GradScaler('cuda')

    train_anchors = torch.FloatTensor(train_dataset.anchors)
    val_anchors = torch.FloatTensor(val_dataset.anchors)

    # 训练循环
    print("\n" + "=" * 60)
    print("开始训练 (500轮)")
    print("=" * 60)

    best_mae = float('inf')
    best_epoch = 0
    history = []

    for epoch in range(500):
        t0 = time.time()

        # 训练
        train_loss = train_epoch(model, train_loader, optimizer, criterion, device, scaler, train_anchors)

        # 验证
        val_metrics = evaluate(model, val_loader, device, val_dataset.anchors)

        scheduler.step()

        # 记录历史
        history.append({
            'epoch': epoch + 1,
            'train_loss': float(train_loss),
            'val_mae': float(val_metrics['mae']),
            'val_clarke_a': float(val_metrics['clarke_a']),
            'key_maes': {k: float(v) for k, v in val_metrics['key_maes'].items()},
        })

        # 打印进度
        status = " ✓最佳" if val_metrics['mae'] < best_mae else ""
        km = val_metrics['key_maes']
        print(f"Epoch {epoch+1:3d}/200 ({time.time()-t0:.0f}s) | "
              f"Loss: {train_loss:.4f} | MAE: {val_metrics['mae']:.3f} | "
              f"Clarke A: {val_metrics['clarke_a']:.1f}%{status}")
        print(f"  5min={km['5min']:.3f} 15min={km['15min']:.3f} "
              f"30min={km['30min']:.3f} 60min={km['60min']:.3f} "
              f"120min={km['120min']:.3f}")

        # 保存最佳模型
        if val_metrics['mae'] < best_mae:
            best_mae = val_metrics['mae']
            best_epoch = epoch + 1
            torch.save({
                'model_state_dict': model.state_dict(),
                'input_dim': train_dataset.features.shape[1],
                'channels': [64, 64, 128],
                'best_mae': best_mae,
                'best_epoch': best_epoch,
            }, os.path.join(data_dir, 'model_curve_v2.pt'))

    # 保存训练历史
    with open(os.path.join(data_dir, 'training_history_curve_v2.json'), 'w') as f:
        json.dump(history, f, indent=2)

    # 测试集评估
    print("\n" + "=" * 60)
    print("测试集评估（完全独立）")
    print("=" * 60)

    checkpoint = torch.load(os.path.join(data_dir, 'model_curve_v2.pt'))
    model.load_state_dict(checkpoint['model_state_dict'])

    test_metrics = evaluate(model, test_loader, device, test_dataset.anchors)

    print(f"测试患者: {len(test_pids)}人")
    print(f"测试样本: {test_metrics['n']}")
    print(f"MAE: {test_metrics['mae']:.3f} mmol/L")
    print(f"Clarke A: {test_metrics['clarke_a']:.1f}%")
    print(f"\n关键时间点MAE:")
    for k, v in test_metrics['key_maes'].items():
        print(f"  {k}: {v:.3f} mmol/L")

    # 保存结果
    results = {
        'train_patients': len(train_pids),
        'val_patients': len(val_pids),
        'test_patients': len(test_pids),
        'best_epoch': best_epoch,
        'best_val_mae': float(best_mae),
        'test_mae': float(test_metrics['mae']),
        'test_clarke_a': float(test_metrics['clarke_a']),
        'test_key_maes': {k: float(v) for k, v in test_metrics['key_maes'].items()},
    }
    with open(os.path.join(data_dir, 'results_curve_v2.json'), 'w') as f:
        json.dump(results, f, indent=2)

    print(f"\n{'='*60}")
    print("训练完成!")
    print(f"{'='*60}")


if __name__ == '__main__':
    main()
