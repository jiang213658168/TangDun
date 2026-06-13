# backend/training/train_smooth_curve.py
# 平滑曲线预测 - 输出曲线参数而非离散点
#
# 关键改进:
# 1. 输出曲线参数（基线、速率、加速度）
# 2. 用参数生成平滑曲线
# 3. 保证曲线连续、可导

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

class CurveParamDataset(Dataset):
    """曲线参数数据集"""

    def __init__(self, data_path, patient_ids, max_samples=2000, horizon_steps=24):
        self.features = []
        self.params = []  # 曲线参数
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

                # 提取未来曲线参数
                future = pdata[i:i+horizon_steps]
                current = pdata[i]

                # 计算曲线参数
                params = extract_curve_params(future, current)

                if np.any(np.isnan(feat)) or np.any(np.isnan(params)):
                    continue

                self.features.append(feat)
                self.params.append(params)
                self.anchors.append(current)

            if (idx + 1) % 20 == 0 or idx == total - 1:
                print(f"  已处理 {idx+1}/{total} 患者, 累计样本: {len(self.features)}")

        self.features = np.array(self.features, dtype=np.float32)
        self.params = np.array(self.params, dtype=np.float32)
        self.anchors = np.array(self.anchors, dtype=np.float32)
        print(f"  完成! 样本数: {len(self.features)}")

    def __len__(self):
        return len(self.features)

    def __getitem__(self, idx):
        return torch.FloatTensor(self.features[idx]), torch.FloatTensor(self.params[idx])


def extract_curve_params(future_values, current_value):
    """提取曲线参数

    用二次多项式拟合未来曲线:
    G(t) = a*t^2 + b*t + c

    参数:
    - c: 当前血糖（已知）
    - b: 变化速率
    - a: 加速度
    """
    t = np.arange(len(future_values))
    # 归一化时间到[0, 1]
    t_norm = t / len(t)

    # 二次多项式拟合
    coeffs = np.polyfit(t_norm, future_values - current_value, 2)

    return np.array([coeffs[0], coeffs[1], coeffs[2]], dtype=np.float32)


def generate_curve(params, current_value, steps=24):
    """用参数生成平滑曲线

    Args:
        params: [a, b, c] 曲线参数
        current_value: 当前血糖值
        steps: 生成步数

    Returns:
        平滑曲线数组
    """
    t = np.linspace(0, 1, steps)
    a, b, c = params

    # 二次多项式
    curve = a * t**2 + b * t + c + current_value

    return curve


# ==================== 模型 ====================

class CurveParamPredictor(nn.Module):
    """曲线参数预测模型"""

    def __init__(self, input_dim=10, hidden_dim=64):
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

        # 输出头（3个参数：a, b, c）
        self.param_head = nn.Sequential(
            nn.Linear(hidden_dim, 32),
            nn.GELU(),
            nn.Linear(32, 3),
        )

    def forward(self, x):
        if x.dim() == 2:
            x = x.unsqueeze(1)

        encoded = self.encoder(x)
        lstm_out, _ = self.lstm(encoded)
        context = lstm_out[:, -1, :]

        params = self.param_head(context)
        return params


# ==================== 损失函数 ====================

class CurveParamLoss(nn.Module):
    """曲线参数损失函数"""

    def __init__(self):
        super().__init__()
        self.mse = nn.MSELoss()

    def forward(self, pred_params, actual_params):
        # 参数损失
        param_loss = self.mse(pred_params, actual_params)

        return param_loss


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

    # 生成曲线并计算MAE
    mae_list = []
    for i in range(min(100, len(pred_params))):
        pred_curve = generate_curve(pred_params[i], anchors[i])
        target_curve = generate_curve(target_params[i], anchors[i])
        mae = np.mean(np.abs(pred_curve - target_curve))
        mae_list.append(mae)

    avg_mae = np.mean(mae_list)

    return {
        'mae': avg_mae,
        'n': len(pred_params),
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
    print("平滑曲线预测训练")
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
    train_dataset = CurveParamDataset(data_path, train_pids)
    print("\n加载验证集...")
    val_dataset = CurveParamDataset(data_path, val_pids)

    train_loader = DataLoader(train_dataset, batch_size=512, shuffle=True, num_workers=4, pin_memory=True)
    val_loader = DataLoader(val_dataset, batch_size=512, num_workers=4, pin_memory=True)

    # 模型
    model = CurveParamPredictor(
        input_dim=train_dataset.features.shape[1],
        hidden_dim=64,
    ).to(device)
    print(f"\n参数: {sum(p.numel() for p in model.parameters()):,}")

    # 训练
    criterion = CurveParamLoss()
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
        metrics = evaluate(model, val_loader, device, val_dataset.anchors)
        scheduler.step()

        history.append({
            'epoch': epoch + 1,
            'loss': float(loss),
            'mae': float(metrics['mae']),
        })

        status = " ✓最佳" if metrics['mae'] < best_mae else ""
        print(f"Epoch {epoch+1:3d}/100 ({time.time()-t0:.0f}s) | "
              f"Loss: {loss:.4f} | MAE: {metrics['mae']:.3f} mmol/L{status}")

        if metrics['mae'] < best_mae:
            best_mae = metrics['mae']
            best_epoch = epoch + 1
            torch.save({
                'model_state_dict': model.state_dict(),
                'input_dim': train_dataset.features.shape[1],
                'hidden_dim': 64,
            }, os.path.join(data_dir, 'model_smooth.pt'))

    # 保存历史
    with open(os.path.join(data_dir, 'training_history_smooth.json'), 'w') as f:
        json.dump(history, f, indent=2)

    # 测试集评估
    print("\n" + "=" * 60)
    print("加载测试集...")
    print("=" * 60)
    test_dataset = CurveParamDataset(data_path, test_pids)
    test_loader = DataLoader(test_dataset, batch_size=512, num_workers=4, pin_memory=True)

    print("\n独立测试集评估...")
    test_metrics = evaluate(model, test_loader, device, test_dataset.anchors)

    print(f"\n测试患者: {len(test_pids)}人")
    print(f"MAE: {test_metrics['mae']:.3f} mmol/L")

    # 保存结果
    results = {
        'train_patients': len(train_pids),
        'val_patients': len(val_pids),
        'test_patients': len(test_pids),
        'best_epoch': best_epoch,
        'best_val_mae': float(best_mae),
        'test_mae': float(test_metrics['mae']),
        'model': 'CurveParamPredictor',
        'output': 'smooth_curve',
    }
    with open(os.path.join(data_dir, 'results_smooth.json'), 'w') as f:
        json.dump(results, f, indent=2)

    print(f"\n{'='*60}")
    print("训练完成!")
    print(f"{'='*60}")


if __name__ == '__main__':
    main()
