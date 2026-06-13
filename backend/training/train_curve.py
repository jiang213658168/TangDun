# backend/training/train_curve.py
# 预测完整血糖曲线（而非单个点）
#
# 关键改进:
# 1. 输出24个点（2小时，每5分钟一个点）
# 2. 使用序列到序列模型
# 3. 保留时间动态信息

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
    """提取10维特征"""
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

    return np.array([f1, f2, f3, f4, f5, f6, f7, f8, f9, f10], dtype=np.float32)


# ==================== 数据集 ====================

class CGMCurveDataset(Dataset):
    """CGM曲线数据集（预测完整曲线）"""

    def __init__(self, data_path, patient_ids, max_samples=2000, horizon_steps=24):
        self.features = []
        self.targets = []  # 未来24个点的血糖值
        self.anchors = []

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

                # 目标：未来24个点的归一化血糖值
                target_window = pdata[i:i+horizon_steps]
                target_norm, _, _ = normalizer.normalize_batch(
                    pdata[max(0, i-288):i+horizon_steps]
                )
                target = target_norm[-horizon_steps:]  # 最后24个点

                if np.any(np.isnan(feat)) or np.any(np.isnan(target)):
                    continue

                self.features.append(feat)
                self.targets.append(target)
                self.anchors.append(pdata[i])

            if (idx + 1) % 20 == 0 or idx == total - 1:
                print(f"  已处理 {idx+1}/{total} 患者, 累计样本: {len(self.features)}")

        self.features = np.array(self.features, dtype=np.float32)
        self.targets = np.array(self.targets, dtype=np.float32)
        self.anchors = np.array(self.anchors, dtype=np.float32)
        print(f"  完成! 样本数: {len(self.features)}")

    def __len__(self):
        return len(self.features)

    def __getitem__(self, idx):
        return torch.FloatTensor(self.features[idx]), torch.FloatTensor(self.targets[idx])


# ==================== 序列到序列模型 ====================

class CurvePredictor(nn.Module):
    """血糖曲线预测模型"""

    def __init__(self, input_dim=10, hidden_dim=64, output_steps=24):
        super().__init__()

        # 编码器
        self.encoder = nn.Sequential(
            nn.Linear(input_dim, hidden_dim),
            nn.LayerNorm(hidden_dim),
            nn.GELU(),
            nn.Linear(hidden_dim, hidden_dim),
            nn.LayerNorm(hidden_dim),
            nn.GELU(),
        )

        # LSTM
        self.lstm = nn.LSTM(
            input_size=hidden_dim,
            hidden_size=hidden_dim,
            num_layers=2,
            batch_first=True,
            dropout=0.1,
        )

        # 解码器（输出24个点）
        self.decoder = nn.Sequential(
            nn.Linear(hidden_dim, hidden_dim),
            nn.GELU(),
            nn.Linear(hidden_dim, output_steps),
        )

    def forward(self, x):
        if x.dim() == 2:
            x = x.unsqueeze(1)

        # 编码
        encoded = self.encoder(x)

        # LSTM
        lstm_out, _ = self.lstm(encoded)
        context = lstm_out[:, -1, :]

        # 解码
        curve = self.decoder(context)
        return curve


# ==================== 损失函数 ====================

class CurveLoss(nn.Module):
    """曲线损失函数（考虑形状和幅度）"""

    def __init__(self, alpha=0.7, beta=0.3):
        super().__init__()
        self.alpha = alpha
        self.beta = beta
        self.mse = nn.MSELoss()

    def forward(self, pred, actual):
        # MSE损失
        mse_loss = self.mse(pred, actual)

        # 形状损失（一阶差分）
        pred_diff = pred[:, 1:] - pred[:, :-1]
        actual_diff = actual[:, 1:] - actual[:, :-1]
        shape_loss = self.mse(pred_diff, actual_diff)

        return self.alpha * mse_loss + self.beta * shape_loss


# ==================== 评估 ====================

@torch.no_grad()
def evaluate(model, loader, device):
    model.eval()
    all_preds = []
    all_targets = []

    for bx, by in loader:
        pred = model(bx.to(device))
        all_preds.extend(pred.cpu().numpy())
        targets = by.cpu().numpy()
        all_targets.extend(targets)

    preds = np.array(all_preds)
    targets = np.array(all_targets)

    # 计算每个时间步的MAE
    mae_per_step = np.mean(np.abs(preds - targets), axis=0)
    rmse_per_step = np.sqrt(np.mean((preds - targets) ** 2, axis=0))

    # 计算整体MAE
    mae = np.mean(np.abs(preds - targets))
    rmse = np.sqrt(np.mean((preds - targets) ** 2))

    # 计算Clarke A区（使用5分钟预测）
    pred_5min = preds[:, 1]  # 5分钟
    target_5min = targets[:, 1]

    clarke_a = sum(1 for p, t in zip(pred_5min, target_5min)
                   if abs(p - t) <= 1.4 / 18.0182)  # 转换为归一化空间

    return {
        'mae': mae,
        'rmse': rmse,
        'mae_per_step': mae_per_step.tolist(),
        'rmse_per_step': rmse_per_step.tolist(),
        'clarke_a_5min': clarke_a / len(pred_5min) * 100,
        'n': len(preds),
    }


# ==================== 训练 ====================

def train_epoch(model, loader, optimizer, criterion, device, scaler):
    model.train()
    total_loss, n = 0, 0
    for bx, by in loader:
        bx, by = bx.to(device), by.to(device)
        with torch.amp.autocast('cuda'):
            pred = model(bx)
            loss = criterion(pred, by)
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
    print("血糖曲线预测训练")
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
    n_train = int(len(patients) * 0.6)
    n_val = int(len(patients) * 0.8)

    train_pids = list(patients[:n_train])
    val_pids = list(patients[n_train:n_val])
    test_pids = list(patients[n_val:])

    print(f"训练: {len(train_pids)} | 验证: {len(val_pids)} | 测试: {len(test_pids)}")

    # GPU
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    print(f"设备: {device}")

    # 加载数据
    print("\n加载训练集...")
    train_dataset = CGMCurveDataset(data_path, train_pids)
    print("\n加载验证集...")
    val_dataset = CGMCurveDataset(data_path, val_pids)

    train_loader = DataLoader(train_dataset, batch_size=256, shuffle=True, num_workers=4, pin_memory=True)
    val_loader = DataLoader(val_dataset, batch_size=256, num_workers=4, pin_memory=True)

    # 模型
    model = CurvePredictor(
        input_dim=train_dataset.features.shape[1],
        hidden_dim=64,
        output_steps=24,
    ).to(device)
    print(f"\n参数: {sum(p.numel() for p in model.parameters()):,}")

    # 训练
    criterion = CurveLoss()
    optimizer = optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingWarmRestarts(optimizer, T_0=10, T_mult=2)
    scaler = torch.amp.GradScaler('cuda')

    print("\n" + "=" * 60)
    print("开始训练 (100轮)...")
    print("=" * 60)

    best_mae = float('inf')
    best_epoch = 0
    history = []

    for epoch in range(100):
        t0 = time.time()
        loss = train_epoch(model, train_loader, optimizer, criterion, device, scaler)
        metrics = evaluate(model, val_loader, device)
        scheduler.step()

        history.append({
            'epoch': epoch + 1,
            'loss': float(loss),
            'mae': float(metrics['mae']),
            'rmse': float(metrics['rmse']),
            'clarke_a_5min': float(metrics['clarke_a_5min']),
        })

        status = " ✓最佳" if metrics['mae'] < best_mae else ""
        print(f"Epoch {epoch+1:3d}/100 ({time.time()-t0:.0f}s) | "
              f"Loss: {loss:.4f} | MAE: {metrics['mae']:.3f} | "
              f"Clarke A(5min): {metrics['clarke_a_5min']:.1f}%{status}")

        if metrics['mae'] < best_mae:
            best_mae = metrics['mae']
            best_epoch = epoch + 1
            torch.save({
                'model_state_dict': model.state_dict(),
                'input_dim': train_dataset.features.shape[1],
                'hidden_dim': 64,
                'output_steps': 24,
            }, os.path.join(data_dir, 'model_curve.pt'))

    # 保存历史
    with open(os.path.join(data_dir, 'training_history_curve.json'), 'w') as f:
        json.dump(history, f, indent=2)

    # 测试集评估
    print("\n" + "=" * 60)
    print("加载测试集...")
    print("=" * 60)
    test_dataset = CGMCurveDataset(data_path, test_pids)
    test_loader = DataLoader(test_dataset, batch_size=256, num_workers=4, pin_memory=True)

    print("\n独立测试集评估...")
    test_metrics = evaluate(model, test_loader, device)

    print(f"\n测试患者: {len(test_pids)}人")
    print(f"整体MAE: {test_metrics['mae']:.3f}")
    print(f"5分钟Clarke A: {test_metrics['clarke_a_5min']:.1f}%")
    print(f"\n各时间步MAE:")
    for i, mae in enumerate(test_metrics['mae_per_step']):
        print(f"  {(i+1)*5}分钟: {mae:.3f}")

    # 保存结果
    results = {
        'train_patients': len(train_pids),
        'val_patients': len(val_pids),
        'test_patients': len(test_pids),
        'best_epoch': best_epoch,
        'best_val_mae': float(best_mae),
        'test_mae': float(test_metrics['mae']),
        'test_clarke_a_5min': float(test_metrics['clarke_a_5min']),
        'output_steps': 24,
        'model': 'CurvePredictor',
    }
    with open(os.path.join(data_dir, 'results_curve.json'), 'w') as f:
        json.dump(results, f, indent=2)

    print(f"\n{'='*60}")
    print("训练完成!")
    print(f"{'='*60}")


if __name__ == '__main__':
    main()
