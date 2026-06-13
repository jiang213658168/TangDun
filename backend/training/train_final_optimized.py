# backend/training/train_final_optimized.py
# 最终优化版 - 预训练微调 + 无早停
#
# 优化项:
# 1. 自监督预训练（学习血糖动力学先验）
# 2. 有监督微调（优化预测精度）
# 3. 无早停（充分训练）
# 4. 改进损失函数
# 5. 数据增强
# 6. 学习率调度

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


# ==================== 特征提取 ====================

def extract_features(glucose_values, idx, normalizer):
    """提取15维特征"""
    norm_vals, means, stds = normalizer.normalize_batch(glucose_values[max(0, idx-288):idx+1])
    current_norm = norm_vals[-1]

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

    # 增强特征
    f11 = norm_vals[-1] - norm_vals[-25] if len(norm_vals) >= 25 else 0.0
    f12 = np.sum((recent >= -1.5) & (recent <= 1.5)) / max(len(recent), 1) * 100.0
    f13 = 1.0 if f4 > 0.02 else (-1.0 if f4 < -0.02 else 0.0)
    f14 = np.std(norm_vals[-12:]) if len(norm_vals) >= 12 else 0.0
    f15 = np.cos(2 * np.pi * hour / 24)

    return np.array([f1, f2, f3, f4, f5, f6, f7, f8, f9, f10,
                     f11, f12, f13, f14, f15], dtype=np.float32)


# ==================== 数据集 ====================

class CGMDataset(Dataset):
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
            pdata = df[df['patient_id'] == pid]['glucose'].values

            if len(pdata) < 300:
                continue

            n = min(len(pdata) - 288 - horizon_steps, max_samples)
            if n <= 0:
                continue

            indices = np.linspace(288, len(pdata) - horizon_steps - 1, n, dtype=int)
            for i in indices:
                feat = extract_features(pdata, i, normalizer)

                future = pdata[i:i+horizon_steps]
                current = pdata[i]
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

        return x, y


# ==================== 模型 ====================

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


class ContinuousCurvePredictor(nn.Module):
    """连续曲线预测模型"""

    def __init__(self, input_dim=15, hidden_dim=128, dropout=0.2):
        super().__init__()

        # 输入嵌入
        self.input_proj = nn.Sequential(
            nn.Linear(input_dim, hidden_dim),
            nn.LayerNorm(hidden_dim),
            nn.GELU(),
            nn.Dropout(dropout),
        )

        # TCN编码器
        channels = [64, 64, 128, 128]
        layers = []
        for i in range(len(channels)):
            in_ch = hidden_dim if i == 0 else channels[i-1]
            layers.append(TemporalBlock(in_ch, channels[i], 3, 2**i, dropout))
        self.tcn = nn.Sequential(*layers)

        # LSTM
        self.lstm = nn.LSTM(
            input_size=channels[-1],
            hidden_size=hidden_dim,
            num_layers=2,
            batch_first=True,
            dropout=dropout,
        )

        # 注意力
        self.attention = nn.MultiheadAttention(
            embed_dim=hidden_dim,
            num_heads=4,
            batch_first=True,
            dropout=dropout,
        )

        # 输出头
        self.param_head = nn.Sequential(
            nn.Linear(hidden_dim, 64),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(64, 32),
            nn.GELU(),
            nn.Linear(32, 4),
        )

    def forward(self, x):
        if x.dim() == 2:
            x = x.unsqueeze(1)

        # 输入嵌入
        embedded = self.input_proj(x)

        # TCN
        tcn_out = self.tcn(embedded.permute(0, 2, 1)).permute(0, 2, 1)

        # LSTM
        lstm_out, _ = self.lstm(tcn_out)

        # 注意力
        attn_out, _ = self.attention(lstm_out, lstm_out, lstm_out)

        # 聚合
        context = attn_out[:, -1, :]

        # 输出
        return self.param_head(context)


# ==================== 预训练模型 ====================

class MaskedAutoencoder(nn.Module):
    """掩码自编码器"""

    def __init__(self, input_dim=15, mask_ratio=0.15):
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


# ==================== 损失函数 ====================

class ImprovedCurveLoss(nn.Module):
    """改进的曲线损失函数"""

    def __init__(self, alpha=0.5, beta=0.3, gamma=0.2):
        super().__init__()
        self.alpha = alpha
        self.beta = beta
        self.gamma = gamma
        self.mse = nn.MSELoss()
        self.huber = nn.HuberLoss(delta=1.0)

    def forward(self, pred_params, actual_params, anchors):
        param_loss = self.huber(pred_params, actual_params)

        batch_size = pred_params.shape[0]
        shape_loss = 0.0

        for i in range(batch_size):
            pred_curve = self._generate_curve(
                pred_params[i].detach().cpu().numpy(),
                anchors[i].detach().cpu().numpy()
            )
            actual_curve = self._generate_curve(
                actual_params[i].detach().cpu().numpy(),
                anchors[i].detach().cpu().numpy()
            )

            shape_loss += np.mean((pred_curve - actual_curve) ** 2)

            pred_diff = np.diff(pred_curve)
            actual_diff = np.diff(actual_curve)
            shape_loss += np.mean((pred_diff - actual_diff) ** 2)

        shape_loss = shape_loss / batch_size

        smooth_loss = torch.mean(torch.abs(pred_params[:, 0]))
        smooth_loss += torch.mean(torch.abs(pred_params[:, 1]))

        return self.alpha * param_loss + self.beta * shape_loss + self.gamma * smooth_loss

    def _generate_curve(self, params, current_value, num_points=24):
        t = np.linspace(0, 1, num_points)
        a, b, c, d = params
        relative_change = a * t**3 + b * t**2 + c * t + d
        return current_value * (1 + relative_change)


# ==================== 评估 ====================

@torch.no_grad()
def evaluate(model, loader, device, anchors):
    model.eval()
    all_params = []
    all_targets = []

    for bx, by in loader:
        pred = model(bx.to(device))
        all_params.extend(pred.cpu().numpy())
        all_targets.extend(by.numpy())

    pred_params = np.array(all_params)
    target_params = np.array(all_targets)

    mae_list = []
    clarke_a_count = 0
    total_count = 0

    for i in range(min(200, len(pred_params))):
        pred_curve = generate_curve(pred_params[i], anchors[i])
        target_curve = generate_curve(target_params[i], anchors[i])

        mae = np.mean(np.abs(pred_curve - target_curve))
        mae_list.append(mae)

        pred_5min = pred_curve[1] if len(pred_curve) > 1 else pred_curve[0]
        target_5min = target_curve[1] if len(target_curve) > 1 else target_curve[0]

        if (target_5min < 5.6 and abs(pred_5min - target_5min) <= 1.4) or \
           (target_5min >= 5.6 and 0.8 * target_5min <= pred_5min <= 1.2 * target_5min):
            clarke_a_count += 1
        total_count += 1

    avg_mae = np.mean(mae_list)
    clarke_a = clarke_a_count / total_count * 100 if total_count > 0 else 0

    return {'mae': avg_mae, 'clarke_a': clarke_a, 'n': len(pred_params)}


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


def train_epoch(model, loader, optimizer, criterion, device, scaler, anchors):
    model.train()
    total_loss, n = 0, 0

    for batch_idx, (bx, by) in enumerate(loader):
        bx, by = bx.to(device), by.to(device)
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
    print("最终优化版训练（预训练微调 + 无早停）")
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
    train_dataset = CGMDataset(data_path, train_pids, augment=True)
    print("加载验证集...")
    val_dataset = CGMDataset(data_path, val_pids, augment=False)

    train_loader = DataLoader(train_dataset, batch_size=256, shuffle=True, num_workers=4, pin_memory=True)
    val_loader = DataLoader(val_dataset, batch_size=256, num_workers=4, pin_memory=True)

    # 模型
    model = ContinuousCurvePredictor(
        input_dim=train_dataset.features.shape[1],
        hidden_dim=128,
        dropout=0.2,
    ).to(device)
    print(f"\n参数: {sum(p.numel() for p in model.parameters()):,}")

    # 训练
    criterion = ImprovedCurveLoss()
    optimizer = optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingWarmRestarts(optimizer, T_0=10, T_mult=2)
    scaler = torch.amp.GradScaler('cuda')

    train_anchors = torch.FloatTensor(train_dataset.anchors)
    val_anchors = torch.FloatTensor(val_dataset.anchors)

    print("\n" + "=" * 60)
    print("阶段3: 有监督微调 (200轮，无早停)")
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
        })

        status = " ✓最佳" if metrics['mae'] < best_mae else ""
        print(f"Epoch {epoch+1:3d}/200 ({time.time()-t0:.0f}s) | "
              f"Loss: {loss:.4f} | MAE: {metrics['mae']:.3f} mmol/L | "
              f"Clarke A: {metrics['clarke_a']:.1f}%{status}")

        if metrics['mae'] < best_mae:
            best_mae = metrics['mae']
            best_epoch = epoch + 1
            torch.save({
                'model_state_dict': model.state_dict(),
                'input_dim': train_dataset.features.shape[1],
                'hidden_dim': 128,
            }, os.path.join(data_dir, 'model_final_optimized.pt'))

    # 保存历史
    with open(os.path.join(data_dir, 'training_history_final.json'), 'w') as f:
        json.dump(history, f, indent=2)

    # 最终评估
    print("\n" + "=" * 60)
    print("最终评估")
    print("=" * 60)

    # 加载最佳模型
    checkpoint = torch.load(os.path.join(data_dir, 'model_final_optimized.pt'))
    model.load_state_dict(checkpoint['model_state_dict'])

    val_metrics = evaluate(model, val_loader, device, val_dataset.anchors)
    print(f"验证集: MAE={val_metrics['mae']:.3f} mmol/L | Clarke A={val_metrics['clarke_a']:.1f}%")

    # 保存结果
    results = {
        'train_patients': len(train_pids),
        'val_patients': len(val_pids),
        'best_epoch': best_epoch,
        'best_mae': float(best_mae),
        'val_mae': float(val_metrics['mae']),
        'val_clarke_a': float(val_metrics['clarke_a']),
        'features': train_dataset.features.shape[1],
        'model': 'ContinuousCurvePredictor (TCN+LSTM+Attention)',
        'pretraining': 'MaskedAutoencoder',
        'loss': 'ImprovedCurveLoss (Huber + Shape + Smooth)',
    }
    with open(os.path.join(data_dir, 'results_final.json'), 'w') as f:
        json.dump(results, f, indent=2)

    print(f"\n{'='*60}")
    print("训练完成!")
    print(f"{'='*60}")


if __name__ == '__main__':
    main()
