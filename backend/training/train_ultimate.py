# backend/training/train_ultimate.py
# 简化曲线预测 - 纯TCN + 关键时间点
#
# 改进:
# 1. 纯TCN模型（去掉LSTM和Attention）
# 2. 10维特征
# 3. 曲线参数预测
# 4. 关键时间点MAE显示

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


# ==================== 归一化 ====================

class CausalSlidingNormalizer:
    def __init__(self, window_points=288, epsilon=1e-6):
        self.window_points = window_points
        self.epsilon = epsilon

    def normalize(self, values, idx):
        start = max(0, idx - self.window_points)
        window = values[start:idx]
        if len(window) < 10:
            mean = np.mean(values[:idx]) if idx > 0 else 8.0
            std = np.std(values[:idx]) if idx > 0 else 3.0
        else:
            mean = np.mean(window)
            std = np.std(window)
        normalized = (values[idx] - mean) / (std + self.epsilon)
        return normalized, mean, std

    def normalize_batch(self, values):
        n = len(values)
        normalized = np.zeros(n)
        means = np.zeros(n)
        stds = np.zeros(n)
        for i in range(n):
            normalized[i], means[i], stds[i] = self.normalize(values, i)
        return normalized, means, stds


# ==================== 10维核心特征 ====================

def extract_features(glucose_values, idx, normalizer,
                     carb_values=None, insulin_values=None,
                     hr_values=None, step_values=None):
    """提取10维核心特征（与模型匹配）

    特征:
    1. 当前血糖值（归一化）
    2. 30分钟变化
    3. 1小时变化
    4. 30分钟ROC
    5. 1小时ROC
    6. 加速度
    7. 6h均值
    8. 6h标准差
    9. TIR
    10. 小时sin编码
    """
    norm_vals, means, stds = normalizer.normalize_batch(glucose_values[max(0, idx-288):idx+1])
    current_norm = norm_vals[-1]

    # === 血糖特征 (1-15) ===
    # 1: 当前值
    f1 = current_norm

    # 2-4: 多时间尺度变化
    f2 = norm_vals[-1] - norm_vals[-7] if len(norm_vals) >= 7 else 0.0  # 30min
    f3 = norm_vals[-1] - norm_vals[-13] if len(norm_vals) >= 13 else 0.0  # 1h
    f4 = norm_vals[-1] - norm_vals[-25] if len(norm_vals) >= 25 else 0.0  # 2h

    # 5-7: 多时间尺度ROC
    f5 = f2 / 30.0 if len(norm_vals) >= 7 else 0.0
    f6 = f3 / 60.0 if len(norm_vals) >= 13 else 0.0
    f7 = f4 / 120.0 if len(norm_vals) >= 25 else 0.0

    # 8: 加速度（二阶导数）
    if len(norm_vals) >= 19:
        s1 = (norm_vals[-1] - norm_vals[-7]) / 30.0
        s2 = (norm_vals[-7] - norm_vals[-13]) / 30.0
        f8 = (s1 - s2) / 30.0
    else:
        f8 = 0.0

    # 9-11: 多时间尺度统计
    recent_6h = norm_vals[-72:] if len(norm_vals) >= 72 else norm_vals
    recent_1h = norm_vals[-13:] if len(norm_vals) >= 13 else norm_vals
    recent_30min = norm_vals[-7:] if len(norm_vals) >= 7 else norm_vals

    f9 = np.mean(recent_6h)
    f10 = np.std(recent_6h)
    f11 = np.std(recent_1h)

    # 12: TIR (归一化空间)
    f12 = np.sum((recent_6h >= -1.5) & (recent_6h <= 1.5)) / max(len(recent_6h), 1) * 100.0

    # 13-14: 峰谷值
    f13 = np.max(norm_vals[-36:]) if len(norm_vals) >= 36 else 0.0
    f14 = np.min(norm_vals[-36:]) if len(norm_vals) >= 36 else 0.0

    # 15: 趋势方向
    f15 = 1.0 if f5 > 0.02 else (-1.0 if f5 < -0.02 else 0.0)

    # === 碳水特征 (16-20) ===
    if carb_values is not None and len(carb_values) > 0:
        carb_4h = carb_values[max(0, idx-48):idx+1]
        carb_2h = carb_values[max(0, idx-24):idx+1]
        carb_1h = carb_values[max(0, idx-12):idx+1]

        f16 = np.sum(carb_4h)  # 4h总碳水
        f17 = np.sum(carb_2h)  # 2h总碳水
        f18 = np.sum(carb_1h)  # 1h总碳水

        # 最近进食时间
        nonzero = np.nonzero(carb_values[max(0, idx-144):idx+1])[0]
        f19 = (len(carb_values[max(0, idx-144):idx+1]) - nonzero[-1]) * 5 if len(nonzero) > 0 else 999

        # 碳水吸收率估计
        f20 = f16 / max(f19, 1) if f19 < 999 else 0.0
    else:
        f16, f17, f18, f19, f20 = 0.0, 0.0, 0.0, 999.0, 0.0

    # === 胰岛素特征 (21-26) ===
    if insulin_values is not None and len(insulin_values) > 0:
        insulin_4h = insulin_values[max(0, idx-48):idx+1]
        insulin_2h = insulin_values[max(0, idx-24):idx+1]
        insulin_1h = insulin_values[max(0, idx-12):idx+1]

        f21 = np.sum(insulin_4h)  # 4h总胰岛素
        f22 = np.sum(insulin_2h)  # 2h总胰岛素
        f23 = np.sum(insulin_1h)  # 1h总胰岛素

        # 最近注射时间
        nonzero = np.nonzero(insulin_values[max(0, idx-144):idx+1])[0]
        f24 = (len(insulin_values[max(0, idx-144):idx+1]) - nonzero[-1]) * 5 if len(nonzero) > 0 else 999

        # IOB估计（简化）
        if f24 < 999:
            f25 = f21 * np.exp(-f24 / 240)  # 4小时衰减
        else:
            f25 = 0.0

        # 胰岛素/碳水比
        f26 = f21 / max(f16, 1) if f16 > 0 else 0.0
    else:
        f21, f22, f23, f24, f25, f26 = 0.0, 0.0, 0.0, 999.0, 0.0, 0.0

    # === 心率特征 (27-30) ===
    if hr_values is not None and len(hr_values) > 0:
        hr_1h = hr_values[max(0, idx-12):idx+1]
        hr_valid = hr_1h[hr_1h > 0]

        if len(hr_valid) > 0:
            f27 = np.mean(hr_valid)  # 平均心率
            f28 = np.std(hr_valid)   # 心率变异性
            f29 = np.max(hr_valid) - np.min(hr_valid)  # 心率范围
            f30 = np.sum(hr_valid > 100) / len(hr_valid) * 100  # 高心率比例
        else:
            f27, f28, f29, f30 = 0.0, 0.0, 0.0, 0.0
    else:
        f27, f28, f29, f30 = 0.0, 0.0, 0.0, 0.0

    # === 步数特征 (31-32) ===
    if step_values is not None and len(step_values) > 0:
        steps_1h = step_values[max(0, idx-12):idx+1]
        steps_4h = step_values[max(0, idx-48):idx+1]

        f31 = np.sum(steps_1h)  # 1h步数
        f32 = np.sum(steps_4h)  # 4h步数
    else:
        f31, f32 = 0.0, 0.0

    # === 交互特征 (33-35) ===
    # 33: 碴水/胰岛素比
    f33 = f16 / max(f21, 1) if f21 > 0 else 0.0

    # 34: 运动后血糖变化
    if f31 > 100:  # 有运动
        glucose_change = norm_vals[-1] - norm_vals[-13] if len(norm_vals) >= 13 else 0
        f34 = glucose_change
    else:
        f34 = 0.0

    # 35: 餐后血糖变化
    if f16 > 10:  # 有进食
        glucose_change = norm_vals[-1] - norm_vals[-7] if len(norm_vals) >= 7 else 0
        f35 = glucose_change
    else:
        f35 = 0.0

    return np.array([f1, f2, f3, f4, f5, f6, f7, f8, f9, f10,
                     f11, f12, f13, f14, f15, f16, f17, f18, f19, f20,
                     f21, f22, f23, f24, f25, f26, f27, f28, f29, f30,
                     f31, f32, f33, f34, f35], dtype=np.float32)


# ==================== 数据集 ====================

class EnhancedDataset(Dataset):
    def __init__(self, data_path, patient_ids, max_samples=3000, horizon_steps=24, augment=False):
        self.features = []
        self.targets = []
        self.anchors = []
        self.augment = augment

        print(f"  加载数据...")
        df = pd.read_csv(data_path)
        df = df[df['patient_id'].isin(patient_ids)]

        total = len(patient_ids)
        normalizer = CausalSlidingNormalizer()

        for idx, pid in enumerate(patient_ids):
            pdata = df[df['patient_id'] == pid]
            glucose_values = pdata['glucose'].values
            carb_values = pdata['carbs'].values if 'carbs' in pdata.columns else None
            insulin_values = pdata['bolus'].values if 'bolus' in pdata.columns else None
            hr_values = pdata['heart_rate'].values if 'heart_rate' in pdata.columns else None
            step_values = pdata['steps'].values if 'steps' in pdata.columns else None

            if len(glucose_values) < 300:
                continue

            n = min(len(glucose_values) - 288 - horizon_steps, max_samples)
            if n <= 0:
                continue

            indices = np.linspace(288, len(glucose_values) - horizon_steps - 1, n, dtype=int)
            for i in indices:
                feat = extract_features(
                    glucose_values, i, normalizer,
                    carb_values, insulin_values, hr_values, step_values
                )

                future = glucose_values[i:i+horizon_steps]
                current = glucose_values[i]
                t = np.linspace(0, 1, horizon_steps)
                normalized_future = (future - current) / current

                try:
                    coeffs = np.polyfit(t, normalized_future, 3)
                    params = coeffs.tolist()
                except:
                    params = [0, 0, 0, 0]

                if np.any(np.isnan(feat)) or np.any(np.isnan(params)):
                    continue

                self.features.append(feat)
                self.targets.append(params)
                self.anchors.append(current)

            if (idx + 1) % 20 == 0 or idx == total - 1:
                print(f"  已处理 {idx+1}/{total} 患者, 累计样本: {len(self.features)}")

        self.features = np.array(self.features, dtype=np.float32)
        self.targets = np.array(self.targets, dtype=np.float32)
        self.anchors = np.array(self.anchors, dtype=np.float32)
        print(f"  完成! 样本数: {len(self.features)}")

    def __len__(self):
        return len(self.features)

    def __getitem__(self, idx):
        x = torch.FloatTensor(self.features[idx])
        y = torch.FloatTensor(self.targets[idx])

        if self.augment:
            if np.random.random() < 0.3:
                x = x + torch.randn_like(x) * 0.01
            if np.random.random() < 0.2:
                x = x * np.random.uniform(0.95, 1.05)
            if np.random.random() < 0.1:
                mask = torch.rand_like(x) < 0.1
                x[mask] = 0

        return x, y


# ==================== 改进的模型 ====================

class ResidualBlock(nn.Module):
    """残差块"""

    def __init__(self, dim, dropout=0.2):
        super().__init__()
        self.block = nn.Sequential(
            nn.Linear(dim, dim),
            nn.LayerNorm(dim),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(dim, dim),
            nn.LayerNorm(dim),
        )
        self.dropout = nn.Dropout(dropout)

    def forward(self, x):
        return x + self.dropout(self.block(x))


class SimpleTCNCurvePredictor(nn.Module):
    """纯TCN曲线预测模型（无LSTM、无Attention）"""

    def __init__(self, input_dim=10, channels=[64, 64, 128], dropout=0.2):
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


class TemporalBlock(nn.Module):
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


class CausalConv1d(nn.Module):
    def __init__(self, in_ch, out_ch, kernel, dilation=1):
        super().__init__()
        self.padding = (kernel - 1) * dilation
        self.conv = nn.Conv1d(in_ch, out_ch, kernel, dilation=dilation, padding=self.padding)

    def forward(self, x):
        return self.conv(x)[:, :, :x.size(2)]


# ==================== Clarke-aware损失函数 ====================

class ClarkeAwareLoss(nn.Module):
    """Clarke-aware损失函数"""

    def __init__(self, alpha=0.5, beta=0.3, gamma=0.2):
        super().__init__()
        self.alpha = alpha
        self.beta = beta
        self.gamma = gamma
        self.mse = nn.MSELoss()
        self.huber = nn.HuberLoss(delta=1.0)

    def forward(self, pred_params, actual_params, anchors):
        # 参数损失
        param_loss = self.huber(pred_params, actual_params)

        # 曲线形状损失
        batch_size = pred_params.shape[0]
        shape_loss = 0.0
        clarke_loss = 0.0

        for i in range(batch_size):
            pred_curve = self._generate_curve(
                pred_params[i].detach().cpu().numpy(),
                anchors[i].detach().cpu().numpy()
            )
            actual_curve = self._generate_curve(
                actual_params[i].detach().cpu().numpy(),
                anchors[i].detach().cpu().numpy()
            )

            # 形状损失
            shape_loss += np.mean((pred_curve - actual_curve) ** 2)

            # Clarke A区损失
            for p, a in zip(pred_curve, actual_curve):
                if a < 5.6:
                    clarke_loss += max(0, abs(p - a) - 1.4)
                else:
                    clarke_loss += max(0, abs(p - a) / a - 0.2)

        shape_loss = shape_loss / batch_size
        clarke_loss = clarke_loss / (batch_size * 24)

        return self.alpha * param_loss + self.beta * shape_loss + self.gamma * clarke_loss

    def _generate_curve(self, params, current_value, num_points=24):
        t = np.linspace(0, 1, num_points)
        a, b, c, d = params
        relative_change = a * t**3 + b * t**2 + c * t + d
        return current_value * (1 + relative_change)


# ==================== 评估 ====================

@torch.no_grad()
def evaluate(model, loader, device, anchors):
    """评估模型（含关键时间点MAE）"""
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
    key_points = {
        '5min': 1,
        '15min': 3,
        '30min': 6,
        '60min': 12,
        '120min': 24,
    }

    mae_list = []
    key_point_maes = {k: [] for k in key_points}
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
                key_point_maes[name].append(abs(pred_curve[idx] - target_curve[idx]))

        # Clarke A区
        for p, t in zip(pred_curve, target_curve):
            if (t < 5.6 and abs(p - t) <= 1.4) or (t >= 5.6 and 0.8 * t <= p <= 1.2 * t):
                clarke_a_count += 1
            total_count += 1

    avg_mae = np.mean(mae_list)
    clarke_a = clarke_a_count / total_count * 100 if total_count > 0 else 0

    # 关键时间点MAE
    key_maes = {}
    for name, maes in key_point_maes.items():
        key_maes[name] = np.mean(maes) if maes else 0

    return {
        'mae': avg_mae,
        'clarke_a': clarke_a,
        'key_maes': key_maes,
        'n': len(pred_params),
    }


def generate_curve(params, current_value, num_points=24):
    t = np.linspace(0, 1, num_points)
    a, b, c, d = params
    relative_change = a * t**3 + b * t**2 + c * t + d
    return current_value * (1 + relative_change)


# ==================== 训练函数 ====================

def pretrain(data_path, patient_ids, device, epochs=5, batch_size=512):
    """自监督预训练"""
    print("\n" + "=" * 60)
    print("阶段1: 自监督预训练")
    print("=" * 60)

    df = pd.read_csv(data_path)
    normalizer = CausalSlidingNormalizer()
    features = []

    for idx, pid in enumerate(patient_ids):
        pdata = df[df['patient_id'] == pid]['glucose'].values
        for i in range(288, min(len(pdata), 1288)):
            feat = extract_features(pdata, i, normalizer)
            if not np.any(np.isnan(feat)):
                features.append(feat)
        if (idx + 1) % 10 == 0:
            print(f"  {idx+1}/{len(patient_ids)} 患者, 样本: {len(features)}")

    if len(features) < 100:
        print("数据不足，跳过")
        return None

    X = torch.FloatTensor(np.array(features)).to(device)
    model = MaskedAutoencoder(input_dim=features[0].shape[0]).to(device)
    optimizer = optim.Adam(model.parameters(), lr=1e-3)

    print(f"样本: {len(features)}, 参数: {sum(p.numel() for p in model.parameters()):,}")
    for epoch in range(epochs):
        total_loss, n = 0, 0
        for i in range(0, len(X), batch_size):
            loss = model(X[i:i+batch_size])
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            total_loss += loss.item()
            n += 1
        print(f"  Epoch {epoch+1}/{epochs}: Loss={total_loss/n:.4f}")

    print("预训练完成!")
    return model.encoder


class MaskedAutoencoder(nn.Module):
    def __init__(self, input_dim=30, mask_ratio=0.15):
        super().__init__()
        self.mask_ratio = mask_ratio
        self.encoder = nn.Sequential(
            nn.Linear(input_dim, 64), nn.GELU(), nn.Linear(64, 32),
        )
        self.decoder = nn.Sequential(
            nn.Linear(32, 64), nn.GELU(), nn.Linear(64, input_dim),
        )

    def forward(self, x):
        mask = torch.rand(x.shape, device=x.device) < self.mask_ratio
        masked_x = x.clone()
        masked_x[mask] = 0
        encoded = self.encoder(masked_x)
        decoded = self.decoder(encoded)
        loss = nn.MSELoss()(decoded[mask], x[mask])
        return loss


def train_epoch(model, loader, optimizer, criterion, device, scaler, anchors, accumulation_steps=4):
    """训练一个epoch（带梯度累积）"""
    model.train()
    total_loss, n = 0, 0
    optimizer.zero_grad(set_to_none=True)

    for batch_idx, (bx, by) in enumerate(loader):
        bx, by = bx.to(device), by.to(device)
        batch_anchors = anchors[batch_idx * len(bx):(batch_idx + 1) * len(bx)].to(device)

        with torch.amp.autocast('cuda'):
            pred = model(bx)
            loss = criterion(pred, by, batch_anchors) / accumulation_steps

        scaler.scale(loss).backward()

        if (batch_idx + 1) % accumulation_steps == 0:
            scaler.unscale_(optimizer)
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            scaler.step(optimizer)
            scaler.update()
            optimizer.zero_grad(set_to_none=True)

        total_loss += loss.item() * accumulation_steps
        n += 1

    return total_loss / n


# ==================== 主函数 ====================

def main():
    data_dir = os.path.join(os.path.dirname(__file__), 'data')
    data_path = os.path.join(data_dir, 'ohio_hupa_data.csv')

    print("=" * 60)
    print("简化曲线预测（纯TCN + 关键时间点）")
    print("=" * 60)

    if not os.path.exists(data_path):
        print("错误: 数据文件不存在")
        return

    # 加载患者
    df = pd.read_csv(data_path, usecols=['patient_id'])
    patients = df['patient_id'].unique()
    print(f"患者: {len(patients)}")
    del df

    # 分割
    np.random.seed(42)
    np.random.shuffle(patients)
    n_train = int(len(patients) * 0.85)

    train_pids = list(patients[:n_train])
    val_pids = list(patients[n_train:])

    print(f"训练: {len(train_pids)} | 验证: {len(val_pids)}")

    # GPU
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    print(f"设备: {device}")

    # 自监督预训练
    pretrain(data_path, train_pids[:50], device, epochs=5)

    # 加载数据
    print("\n" + "=" * 60)
    print("阶段2: 加载数据")
    print("=" * 60)

    print("加载训练集...")
    train_dataset = EnhancedDataset(data_path, train_pids, augment=True)
    print("加载验证集...")
    val_dataset = EnhancedDataset(data_path, val_pids, augment=False)

    train_loader = DataLoader(train_dataset, batch_size=256, shuffle=True, num_workers=4, pin_memory=True)
    val_loader = DataLoader(val_dataset, batch_size=256, num_workers=4, pin_memory=True)

    # 模型（纯TCN）
    model = SimpleTCNCurvePredictor(
        input_dim=train_dataset.features.shape[1],
        channels=[64, 64, 128],
        dropout=0.2,
    ).to(device)
    print(f"\n参数: {sum(p.numel() for p in model.parameters()):,}")

    # 训练
    criterion = ClarkeAwareLoss()
    optimizer = optim.AdamW(model.parameters(), lr=5e-3, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingWarmRestarts(optimizer, T_0=10, T_mult=2)
    scaler = torch.amp.GradScaler('cuda')

    train_anchors = torch.FloatTensor(train_dataset.anchors)
    val_anchors = torch.FloatTensor(val_dataset.anchors)

    print("\n" + "=" * 60)
    print("阶段3: 有监督微调 (200轮)")
    print("=" * 60)

    best_mae = float('inf')
    best_epoch = 0
    history = []

    for epoch in range(200):
        t0 = time.time()
        loss = train_epoch(model, train_loader, optimizer, criterion, device, scaler, train_anchors)
        metrics = evaluate(model, val_loader, device, val_dataset.anchors)
        scheduler.step()

        history.append({
            'epoch': epoch + 1,
            'loss': float(loss),
            'mae': float(metrics['mae']),
            'clarke_a': float(metrics['clarke_a']),
            'key_maes': {k: float(v) for k, v in metrics['key_maes'].items()},
        })

        status = " ✓最佳" if metrics['mae'] < best_mae else ""
        km = metrics['key_maes']
        print(f"Epoch {epoch+1:3d}/200 ({time.time()-t0:.0f}s) | "
              f"Loss: {loss:.4f} | MAE: {metrics['mae']:.3f} | "
              f"Clarke A: {metrics['clarke_a']:.1f}%{status}")
        print(f"  5min={km['5min']:.3f} 15min={km['15min']:.3f} "
              f"30min={km['30min']:.3f} 60min={km['60min']:.3f} "
              f"120min={km['120min']:.3f}")

        if metrics['mae'] < best_mae:
            best_mae = metrics['mae']
            best_epoch = epoch + 1
            torch.save({
                'model_state_dict': model.state_dict(),
                'input_dim': train_dataset.features.shape[1],
                'channels': [64, 64, 128],
            }, os.path.join(data_dir, 'model_ultimate.pt'))

    # 保存历史
    with open(os.path.join(data_dir, 'training_history_ultimate.json'), 'w') as f:
        json.dump(history, f, indent=2)

    # 最终评估
    print("\n" + "=" * 60)
    print("最终评估")
    print("=" * 60)

    checkpoint = torch.load(os.path.join(data_dir, 'model_ultimate.pt'))
    model.load_state_dict(checkpoint['model_state_dict'])

    val_metrics = evaluate(model, val_loader, device, val_dataset.anchors)
    print(f"验证集: MAE={val_metrics['mae']:.3f} | Clarke A={val_metrics['clarke_a']:.1f}%")

    # 保存结果
    results = {
        'train_patients': len(train_pids),
        'val_patients': len(val_pids),
        'best_epoch': best_epoch,
        'best_mae': float(best_mae),
        'val_mae': float(val_metrics['mae']),
        'val_clarke_a': float(val_metrics['clarke_a']),
        'features': train_dataset.features.shape[1],
        'model': 'UltimateCurvePredictor',
        'optimizations': 'pretraining + clarke_loss + residual + attention + augmentation',
    }
    with open(os.path.join(data_dir, 'results_ultimate.json'), 'w') as f:
        json.dump(results, f, indent=2)

    print(f"\n{'='*60}")
    print("训练完成!")
    print(f"{'='*60}")


if __name__ == '__main__':
    main()
