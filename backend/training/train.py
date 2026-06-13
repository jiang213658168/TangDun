# backend/training/train.py
# 最终优化版 - 基于数据特征的算法优化
#
# 核心改进:
# 1. 缺失数据感知（胰岛素/碳水缺失时用代理特征）
# 2. 虚拟事件检测（从血糖曲线推断进餐/注射）
# 3. 多尺度特征（短期+中期+长期）
# 4. 患者统计特征（基线血糖、变异系数）

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
    """24h因果滑动Z-Score归一化"""

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


# ==================== 虚拟事件检测 ====================

class VirtualEventDetector:
    """从血糖曲线推断进餐/胰岛素事件"""

    def detect_meal(self, glucose_values, idx):
        """检测可能的进餐事件"""
        if idx < 12:
            return 0.0, 999.0

        # 检测血糖上升
        slope_30 = (glucose_values[idx] - glucose_values[idx-6]) / 30.0
        slope_60 = (glucose_values[idx] - glucose_values[idx-12]) / 60.0

        # 进餐概率：血糖持续上升
        if slope_30 > 0.05 and slope_60 > 0.02:
            meal_prob = min(1.0, slope_30 * 10)
        else:
            meal_prob = 0.0

        # 搜索最近的上升起点
        time_since = 999.0
        for i in range(idx-1, max(0, idx-144), -1):
            if i >= 6:
                s = (glucose_values[i] - glucose_values[i-6]) / 30.0
                if s > 0.05:
                    time_since = float((idx - i) * 5)
                    break

        return meal_prob, min(time_since, 999.0)

    def detect_insulin(self, glucose_values, idx):
        """检测可能的胰岛素注射事件"""
        if idx < 12:
            return 0.0, 999.0

        # 检测血糖下降
        slope_30 = (glucose_values[idx] - glucose_values[idx-6]) / 30.0
        slope_60 = (glucose_values[idx] - glucose_values[idx-12]) / 60.0

        # 胰岛素概率：血糖持续下降
        if slope_30 < -0.05 and slope_60 < -0.02:
            insulin_prob = min(1.0, -slope_30 * 10)
        else:
            insulin_prob = 0.0

        # 搜索最近的下降起点
        time_since = 999.0
        for i in range(idx-1, max(0, idx-144), -1):
            if i >= 6:
                s = (glucose_values[i] - glucose_values[i-6]) / 30.0
                if s < -0.05:
                    time_since = float((idx - i) * 5)
                    break

        return insulin_prob, min(time_since, 999.0)


# ==================== 特征提取 ====================

def extract_features(glucose_values, idx, normalizer, event_detector,
                     insulin_values=None, carb_values=None,
                     hr_values=None, step_values=None):
    """提取22维特征

    特征分组:
    - 血糖动态 (1-10): 归一化值、变化量、ROC、加速度、统计
    - 胰岛素 (11-13): 实际IOB或虚拟IOB
    - 碳水 (14-16): 实际COB或虚拟COB
    - 运动 (17-19): 心率、步数、运动强度
    - 患者统计 (20-22): 基线、变异系数、TIR
    """
    # 归一化
    norm_vals, means, stds = normalizer.normalize_batch(glucose_values[max(0, idx-288):idx+1])
    current_norm = norm_vals[-1]

    # === 血糖动态特征 (1-10) ===
    f1 = current_norm
    f2 = norm_vals[-1] - norm_vals[-7] if len(norm_vals) >= 7 else 0.0
    f3 = norm_vals[-1] - norm_vals[-13] if len(norm_vals) >= 13 else 0.0
    f4 = f2 / 30.0 if len(norm_vals) >= 7 else 0.0
    f5 = f3 / 60.0 if len(norm_vals) >= 13 else 0.0

    if len(norm_vals) >= 19:
        s1 = (norm_vals[-1] - norm_vals[-7]) / 30.0
        s2 = (norm_vals[-7] - norm_vals[-13]) / 30.0
        f6 = (s1 - s2) / 30.0
    else:
        f6 = 0.0

    recent = norm_vals[-72:] if len(norm_vals) >= 72 else norm_vals
    f7 = np.mean(recent)
    f8 = np.std(recent)
    f9 = np.sum((recent >= -1.5) & (recent <= 1.5)) / max(len(recent), 1) * 100.0

    hour = (idx * 5 / 60) % 24
    f10 = np.sin(2 * np.pi * hour / 24)

    # === 胰岛素特征 (11-13) ===
    has_insulin = insulin_values is not None and np.any(insulin_values > 0)

    if has_insulin:
        # 使用实际胰岛素数据
        insulin_4h = insulin_values[max(0, idx-48):idx+1]
        insulin_1h = insulin_values[max(0, idx-12):idx+1]
        f11 = np.sum(insulin_4h)  # 4h总胰岛素
        f12 = np.sum(insulin_1h)  # 1h总胰岛素
        nonzero = np.nonzero(insulin_values[max(0, idx-144):idx+1])[0]
        f13 = (len(insulin_values[max(0, idx-144):idx+1]) - nonzero[-1]) * 5 if len(nonzero) > 0 else 999
    else:
        # 使用虚拟胰岛素检测
        insulin_prob, time_since = event_detector.detect_insulin(glucose_values, idx)
        f11 = insulin_prob * 10  # 代理：胰岛素概率
        f12 = insulin_prob * 5   # 代理：近期概率
        f13 = time_since

    # === 碳水特征 (14-16) ===
    has_carbs = carb_values is not None and np.any(carb_values > 0)

    if has_carbs:
        # 使用实际碳水数据
        carb_4h = carb_values[max(0, idx-48):idx+1]
        carb_1h = carb_values[max(0, idx-12):idx+1]
        f14 = np.sum(carb_4h)
        f15 = np.sum(carb_1h)
        nonzero = np.nonzero(carb_values[max(0, idx-144):idx+1])[0]
        f16 = (len(carb_values[max(0, idx-144):idx+1]) - nonzero[-1]) * 5 if len(nonzero) > 0 else 999
    else:
        # 使用虚拟碳水检测
        meal_prob, time_since = event_detector.detect_meal(glucose_values, idx)
        f14 = meal_prob * 50  # 代理：进餐概率
        f15 = meal_prob * 20  # 代理：近期概率
        f16 = time_since

    # === 运动特征 (17-19) ===
    if hr_values is not None and len(hr_values) > 0:
        hr_recent = hr_values[max(0, idx-12):idx+1]
        hr_valid = hr_recent[hr_recent > 0]
        f17 = (np.mean(hr_valid) - 70) / 30 if len(hr_valid) > 0 else 0.0

        if len(hr_valid) >= 2:
            f19 = (np.max(hr_valid) - np.min(hr_valid)) / 30
        else:
            f19 = 0.0
    else:
        f17 = 0.0
        f19 = 0.0

    if step_values is not None and len(step_values) > 0:
        steps_1h = step_values[max(0, idx-12):idx+1]
        f18 = np.sum(steps_1h) / 1000
    else:
        f18 = 0.0

    # === 患者统计特征 (20-24) ===
    # 基线血糖（患者长期均值）
    f20 = np.mean(glucose_values[max(0, idx-288):idx]) if idx >= 288 else np.mean(glucose_values[:idx])

    # 变异系数
    f21 = np.std(glucose_values[max(0, idx-288):idx]) / f20 if idx >= 288 and f20 > 0 else 0.0

    # TIR（目标范围内比例）
    recent_24h = glucose_values[max(0, idx-288):idx]
    f22 = np.sum((recent_24h >= 3.9) & (recent_24h <= 10.0)) / max(len(recent_24h), 1) * 100.0

    # 趋势特征
    if len(norm_vals) >= 12:
        f23 = np.mean(np.diff(norm_vals[-12:]))  # 平均变化率
        f24 = np.std(np.diff(norm_vals[-12:]))   # 变化率波动
    else:
        f23 = 0.0
        f24 = 0.0

    # === 时间编码 (25-28) ===
    hour = (idx * 5 / 60) % 24
    day_of_week = (idx * 5 / 60 / 24) % 7

    f25 = np.sin(2 * np.pi * hour / 24)
    f26 = np.cos(2 * np.pi * hour / 24)
    f27 = np.sin(2 * np.pi * day_of_week / 7)
    f28 = np.cos(2 * np.pi * day_of_week / 7)

    return np.array([f1, f2, f3, f4, f5, f6, f7, f8, f9, f10,
                     f11, f12, f13, f14, f15, f16, f17, f18, f19, f20,
                     f21, f22, f23, f24, f25, f26, f27, f28],
                    dtype=np.float32)


# ==================== TCN模型 ====================

class CausalConv1d(nn.Module):
    def __init__(self, in_ch, out_ch, kernel, dilation=1):
        super().__init__()
        self.padding = (kernel - 1) * dilation
        self.conv = nn.Conv1d(in_ch, out_ch, kernel, dilation=dilation, padding=self.padding)

    def forward(self, x):
        return self.conv(x)[:, :, :x.size(2)]


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


class LSTMAttentionModel(nn.Module):
    """LSTM + 注意力机制模型（多任务输出）"""

    def __init__(self, input_dim=22, hidden_dim=128, num_layers=2, dropout=0.2):
        super().__init__()

        # 输入投影
        self.input_proj = nn.Sequential(
            nn.Linear(input_dim, hidden_dim),
            nn.LayerNorm(hidden_dim),
            nn.GELU(),
        )

        # LSTM
        self.lstm = nn.LSTM(
            input_size=hidden_dim,
            hidden_size=hidden_dim,
            num_layers=num_layers,
            batch_first=True,
            dropout=dropout if num_layers > 1 else 0,
        )

        # 注意力层
        self.attention = nn.Sequential(
            nn.Linear(hidden_dim, hidden_dim),
            nn.Tanh(),
            nn.Linear(hidden_dim, 1),
        )

        # 多任务输出头（30/60/90/120分钟）
        self.head_30 = nn.Sequential(
            nn.Linear(hidden_dim, 64), nn.GELU(), nn.Dropout(dropout),
            nn.Linear(64, 1),
        )
        self.head_60 = nn.Sequential(
            nn.Linear(hidden_dim, 64), nn.GELU(), nn.Dropout(dropout),
            nn.Linear(64, 1),
        )
        self.head_90 = nn.Sequential(
            nn.Linear(hidden_dim, 64), nn.GELU(), nn.Dropout(dropout),
            nn.Linear(64, 1),
        )
        self.head_120 = nn.Sequential(
            nn.Linear(hidden_dim, 64), nn.GELU(), nn.Dropout(dropout),
            nn.Linear(64, 1),
        )

    def forward(self, x, horizon=30):
        if x.dim() == 2:
            x = x.unsqueeze(1)

        # 输入投影
        x = self.input_proj(x)

        # LSTM
        lstm_out, _ = self.lstm(x)

        # 注意力权重
        attn_weights = self.attention(lstm_out)
        attn_weights = torch.softmax(attn_weights, dim=1)

        # 加权求和
        context = torch.sum(lstm_out * attn_weights, dim=1)

        # 多任务输出
        if horizon == 30:
            return self.head_30(context).squeeze(-1)
        elif horizon == 60:
            return self.head_60(context).squeeze(-1)
        elif horizon == 90:
            return self.head_90(context).squeeze(-1)
        elif horizon == 120:
            return self.head_120(context).squeeze(-1)
        else:
            return self.head_30(context).squeeze(-1)


# ==================== Clarke Loss ====================

class ClarkeLoss(nn.Module):
    """Clarke-aware损失函数"""

    def __init__(self, alpha=0.7, beta=0.3):
        super().__init__()
        self.alpha = alpha
        self.beta = beta
        self.mse = nn.MSELoss()

    def forward(self, pred, actual):
        mse = self.mse(pred, actual)
        error = torch.abs(pred - actual)
        penalty = torch.where(error > 1.4, error - 1.4, torch.zeros_like(error))
        return self.alpha * mse + self.beta * torch.mean(penalty)


# ==================== 数据集 ====================

class CGMDataset(Dataset):
    """CGM数据集（多任务：30/60/90/120分钟）"""

    def __init__(self, data_path, patient_ids, max_samples=3000):
        self.features = []
        self.targets_30 = []
        self.targets_60 = []
        self.targets_90 = []
        self.targets_120 = []
        self.anchors = []

        print(f"  加载数据...")
        df = pd.read_csv(data_path)
        df = df[df['patient_id'].isin(patient_ids)]

        total = len(patient_ids)
        normalizer = CausalSlidingNormalizer()
        event_detector = VirtualEventDetector()

        for idx, pid in enumerate(patient_ids):
            pdata = df[df['patient_id'] == pid]
            glucose_values = pdata['glucose'].values
            insulin_values = pdata['bolus'].values if 'bolus' in pdata.columns else None
            carb_values = pdata['carbs'].values if 'carbs' in pdata.columns else None
            hr_values = pdata['heart_rate'].values if 'heart_rate' in pdata.columns else None
            step_values = pdata['steps'].values if 'steps' in pdata.columns else None

            if len(glucose_values) < 300:
                continue

            # 需要至少120分钟的数据
            n = min(len(glucose_values) - 288 - 24, max_samples)  # 24 = 120min / 5min
            if n <= 0:
                continue

            indices = np.linspace(288, len(glucose_values) - 25, n, dtype=int)
            for i in indices:
                feat = extract_features(
                    glucose_values, i, normalizer, event_detector,
                    insulin_values, carb_values, hr_values, step_values
                )

                if np.any(np.isnan(feat)):
                    continue

                # 多任务目标
                target_30 = glucose_values[i + 6] if i + 6 < len(glucose_values) else np.nan
                target_60 = glucose_values[i + 12] if i + 12 < len(glucose_values) else np.nan
                target_90 = glucose_values[i + 18] if i + 18 < len(glucose_values) else np.nan
                target_120 = glucose_values[i + 24] if i + 24 < len(glucose_values) else np.nan

                if np.isnan(target_30) or np.isnan(target_60) or np.isnan(target_90) or np.isnan(target_120):
                    continue

                self.features.append(feat)
                self.targets_30.append(target_30)
                self.targets_60.append(target_60)
                self.targets_90.append(target_90)
                self.targets_120.append(target_120)
                self.anchors.append(glucose_values[i])

            if (idx + 1) % 50 == 0 or idx == total - 1:
                print(f"  已处理 {idx+1}/{total} 患者, 累计样本: {len(self.features)}")

        self.features = np.array(self.features, dtype=np.float32)
        self.targets_30 = np.array(self.targets_30, dtype=np.float32)
        self.targets_60 = np.array(self.targets_60, dtype=np.float32)
        self.targets_90 = np.array(self.targets_90, dtype=np.float32)
        self.targets_120 = np.array(self.targets_120, dtype=np.float32)
        self.anchors = np.array(self.anchors, dtype=np.float32)
        print(f"  完成! 样本数: {len(self.features)}")

    def __len__(self):
        return len(self.features)

    def __getitem__(self, idx):
        return (
            torch.FloatTensor(self.features[idx]),
            self.targets_30[idx],
            self.targets_60[idx],
            self.targets_90[idx],
            self.targets_120[idx],
        )


# ==================== 自监督预训练 ====================

class MaskedAutoencoder(nn.Module):
    def __init__(self, input_dim=22, mask_ratio=0.15):
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


def pretrain(data_path, patient_ids, device, epochs=3, batch_size=512):
    """自监督预训练"""
    print("\n" + "=" * 60)
    print("阶段1: 自监督预训练")
    print("=" * 60)

    df = pd.read_csv(data_path)
    normalizer = CausalSlidingNormalizer()
    event_detector = VirtualEventDetector()
    features = []

    for idx, pid in enumerate(patient_ids):
        pdata = df[df['patient_id'] == pid]
        glucose_values = pdata['glucose'].values
        insulin_values = pdata['bolus'].values if 'bolus' in pdata.columns else None
        carb_values = pdata['carbs'].values if 'carbs' in pdata.columns else None
        hr_values = pdata['heart_rate'].values if 'heart_rate' in pdata.columns else None
        step_values = pdata['steps'].values if 'steps' in pdata.columns else None

        for i in range(288, min(len(glucose_values), 1288)):
            feat = extract_features(
                glucose_values, i, normalizer, event_detector,
                insulin_values, carb_values, hr_values, step_values
            )
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


# ==================== 评估 ====================

@torch.no_grad()
def evaluate(model, loader, device, horizon=30):
    model.eval()
    preds, targets = [], []
    for batch in loader:
        bx = batch[0].to(device)
        # 根据horizon选择目标
        if horizon == 30:
            by = batch[1]
        elif horizon == 60:
            by = batch[2]
        elif horizon == 90:
            by = batch[3]
        else:
            by = batch[4]

        pred = model(bx, horizon=horizon)
        preds.extend(pred.cpu().numpy())
        targets.extend(by.numpy())

    preds, targets = np.array(preds), np.array(targets)
    mae = np.mean(np.abs(preds - targets))
    rmse = np.sqrt(np.mean((preds - targets) ** 2))
    clarke_a = sum(1 for p, t in zip(preds, targets)
                   if (t < 5.6 and abs(p - t) <= 1.4) or (t >= 5.6 and 0.8 * t <= p <= 1.2 * t))
    return {'mae': mae, 'rmse': rmse, 'clarke_a': clarke_a / len(preds) * 100, 'n': len(preds)}


# ==================== 训练 ====================

def train_epoch(model, loader, optimizer, criterion, device, scaler):
    model.train()
    total_loss, n = 0, 0
    for batch in loader:
        bx = batch[0].to(device)
        by_30 = batch[1].to(device)
        by_60 = batch[2].to(device)
        by_90 = batch[3].to(device)
        by_120 = batch[4].to(device)

        with torch.amp.autocast('cuda'):
            # 多任务损失
            pred_30 = model(bx, horizon=30)
            pred_60 = model(bx, horizon=60)
            pred_90 = model(bx, horizon=90)
            pred_120 = model(bx, horizon=120)

            loss_30 = criterion(pred_30, by_30)
            loss_60 = criterion(pred_60, by_60)
            loss_90 = criterion(pred_90, by_90)
            loss_120 = criterion(pred_120, by_120)

            # 加权多任务损失（30分钟权重最高）
            loss = 0.4 * loss_30 + 0.3 * loss_60 + 0.2 * loss_90 + 0.1 * loss_120

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
    print("糖盾模型训练（优化版）")
    print("=" * 60)

    if not os.path.exists(data_path):
        print("错误: 数据文件不存在")
        return

    # 加载患者
    df = pd.read_csv(data_path, usecols=['patient_id'])
    patients = df['patient_id'].unique()
    print(f"患者: {len(patients)}")
    del df

    # LOSO分割
    np.random.seed(42)
    np.random.shuffle(patients)
    n_train = int(len(patients) * 0.6)
    n_val = int(len(patients) * 0.8)

    train_pids = list(patients[:n_train])
    val_pids = list(patients[n_train:n_val])
    test_pids = list(patients[n_val:])

    print(f"训练: {len(train_pids)} | 验证: {len(val_pids)} | 测试: {len(test_pids)}")

    # GPU
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    print(f"设备: {device}")

    # 自监督预训练
    pretrain(data_path, train_pids[:50], device)

    # 加载数据
    print("\n" + "=" * 60)
    print("加载训练集...")
    print("=" * 60)
    t0 = time.time()
    train_dataset = CGMDataset(data_path, train_pids)
    print(f"耗时: {time.time()-t0:.0f}s")

    print("\n加载验证集...")
    t0 = time.time()
    val_dataset = CGMDataset(data_path, val_pids)
    print(f"耗时: {time.time()-t0:.0f}s")

    train_loader = DataLoader(train_dataset, batch_size=2048, shuffle=True, num_workers=4, pin_memory=True)
    val_loader = DataLoader(val_dataset, batch_size=2048, num_workers=4, pin_memory=True)

    # 模型
    model = LSTMAttentionModel(input_dim=train_dataset.features.shape[1]).to(device)
    print(f"\n参数: {sum(p.numel() for p in model.parameters()):,}")
    print(f"特征维度: {train_dataset.features.shape[1]}")

    # 训练
    criterion = nn.HuberLoss(delta=1.0)  # Huber Loss更鲁棒
    optimizer = optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingWarmRestarts(optimizer, T_0=10, T_mult=2)
    scaler = torch.amp.GradScaler('cuda')

    print("\n" + "=" * 60)
    print("开始训练 (300轮)...")
    print("=" * 60)

    best_mae = float('inf')
    best_epoch = 0
    history = []

    for epoch in range(300):
        t0 = time.time()
        loss = train_epoch(model, train_loader, optimizer, criterion, device, scaler)

        # 评估所有时域
        metrics_30 = evaluate(model, val_loader, device, horizon=30)
        metrics_60 = evaluate(model, val_loader, device, horizon=60)
        metrics_90 = evaluate(model, val_loader, device, horizon=90)
        metrics_120 = evaluate(model, val_loader, device, horizon=120)
        scheduler.step()

        history.append({
            'epoch': epoch + 1,
            'loss': float(loss),
            'mae_30': float(metrics_30['mae']),
            'clarke_a_30': float(metrics_30['clarke_a']),
            'mae_60': float(metrics_60['mae']),
            'clarke_a_60': float(metrics_60['clarke_a']),
            'mae_90': float(metrics_90['mae']),
            'clarke_a_90': float(metrics_90['clarke_a']),
            'mae_120': float(metrics_120['mae']),
            'clarke_a_120': float(metrics_120['clarke_a']),
        })

        status = " ✓最佳" if metrics_30['mae'] < best_mae else ""
        print(f"Epoch {epoch+1:3d}/300 ({time.time()-t0:.0f}s) | Loss: {loss:.4f}")
        print(f"  30min:  MAE={metrics_30['mae']:.3f} | Clarke A={metrics_30['clarke_a']:.1f}%{status}")
        print(f"  60min:  MAE={metrics_60['mae']:.3f} | Clarke A={metrics_60['clarke_a']:.1f}%")
        print(f"  90min:  MAE={metrics_90['mae']:.3f} | Clarke A={metrics_90['clarke_a']:.1f}%")
        print(f"  120min: MAE={metrics_120['mae']:.3f} | Clarke A={metrics_120['clarke_a']:.1f}%")

        if metrics_30['mae'] < best_mae:
            best_mae = metrics_30['mae']
            best_epoch = epoch + 1
            torch.save({
                'model_state_dict': model.state_dict(),
                'input_dim': train_dataset.features.shape[1],
                'hidden_dim': 128,
                'num_layers': 2,
            }, os.path.join(data_dir, 'model.pt'))

    # 保存历史
    with open(os.path.join(data_dir, 'training_history.json'), 'w') as f:
        json.dump(history, f, indent=2)

    # 测试集评估
    print("\n" + "=" * 60)
    print("加载测试集...")
    print("=" * 60)
    test_dataset = CGMDataset(data_path, test_pids)
    test_loader = DataLoader(test_dataset, batch_size=2048, num_workers=4, pin_memory=True)

    print("\n独立测试集评估...")
    for horizon in [30, 60, 90, 120]:
        test_metrics = evaluate(model, test_loader, device, horizon=horizon)
        print(f"  {horizon}分钟: MAE={test_metrics['mae']:.3f} | RMSE={test_metrics['rmse']:.3f} | Clarke A={test_metrics['clarke_a']:.1f}%")

    # 保存结果
    results = {
        'train_patients': len(train_pids),
        'val_patients': len(val_pids),
        'test_patients': len(test_pids),
        'best_epoch': best_epoch,
        'best_val_mae': float(best_mae),
        'features': 22,
        'model': 'LSTM + Attention (Multi-task)',
        'horizons': [30, 60, 90, 120],
    }
    with open(os.path.join(data_dir, 'results.json'), 'w') as f:
        json.dump(results, f, indent=2)

    print(f"\n{'='*60}")
    print("训练完成!")
    print(f"{'='*60}")


if __name__ == '__main__':
    main()
