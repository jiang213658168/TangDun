# backend/train_ensemble.py
# 集成训练：3个TCN模型 + 预训练微调

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
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


# ==================== 按患者归一化 ====================

class PerPatientNormalizer:
    def __init__(self):
        self.stats = {}

    def fit(self, pid, values):
        self.stats[pid] = {
            'mean': np.mean(values),
            'std': np.std(values) if np.std(values) > 0 else 1.0,
        }

    def transform(self, pid, values):
        if pid not in self.stats:
            return (values - 8.0) / 3.0
        s = self.stats[pid]
        return (values - s['mean']) / s['std']


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


class TCNModel(nn.Module):
    def __init__(self, input_dim=21, channels=[64, 64, 128, 128], kernel=3, dropout=0.2):
        super().__init__()
        layers = []
        for i in range(len(channels)):
            in_ch = input_dim if i == 0 else channels[i-1]
            layers.append(TemporalBlock(in_ch, channels[i], kernel, 2**i, dropout))
        self.network = nn.Sequential(*layers)
        self.head = nn.Sequential(
            nn.AdaptiveAvgPool1d(1), nn.Flatten(),
            nn.Linear(channels[-1], 64), nn.ReLU(), nn.Dropout(dropout),
            nn.Linear(64, 1),
        )

    def forward(self, x):
        if x.dim() == 2:
            x = x.unsqueeze(1)
        return self.head(self.network(x.permute(0, 2, 1))).squeeze(-1)


# ==================== Clarke损失 ====================

class ClarkeLoss(nn.Module):
    def __init__(self, alpha=0.7, beta=0.3):
        super().__init__()
        self.alpha = alpha
        self.beta = beta
        self.mse = nn.MSELoss()

    def forward(self, pred, actual):
        mse_loss = self.mse(pred, actual)
        error = torch.abs(pred - actual)
        low_mask = actual < 5.6
        high_mask = actual >= 5.6
        penalty_low = torch.where(low_mask & (error > 1.4), error - 1.4, torch.zeros_like(error))
        penalty_high = torch.where(
            high_mask & (pred > 1.2 * actual), pred - 1.2 * actual,
            torch.where(high_mask & (pred < 0.8 * actual), 0.8 * actual - pred, torch.zeros_like(error))
        )
        clarke_loss = torch.mean(penalty_low + penalty_high)
        return self.alpha * mse_loss + self.beta * clarke_loss


# ==================== 数据集 ====================

class CGMDataset(Dataset):
    def __init__(self, data_path, patient_ids, normalizer, max_samples=3000):
        features, targets, anchors = [], [], []
        patient_data = {}

        for chunk in pd.read_csv(data_path, chunksize=50000):
            chunk = chunk[chunk['patient_id'].isin(patient_ids)]
            for pid, group in chunk.groupby('patient_id'):
                if pid not in patient_data:
                    patient_data[pid] = {'glucose': [], 'carbs': [], 'bolus': []}
                patient_data[pid]['glucose'].extend(group['glucose'].values.tolist())
                if 'carbs' in group.columns:
                    patient_data[pid]['carbs'].extend(group['carbs'].values.tolist())
                if 'bolus' in group.columns:
                    patient_data[pid]['bolus'].extend(group['bolus'].values.tolist())

        for pid, data in patient_data.items():
            gv = np.array(data['glucose'])
            cv = np.array(data.get('carbs', [0]*len(gv)))
            iv = np.array(data.get('bolus', [0]*len(gv)))

            normalizer.fit(pid, gv)
            ng = normalizer.transform(pid, gv)

            window, horizon = 288, 12
            n = min(len(ng) - window - horizon, max_samples)
            if n <= 0:
                continue

            indices = np.linspace(window, len(ng) - horizon - 1, n, dtype=int)
            for i in indices:
                w = ng[i-window:i]
                c = ng[i]
                feat = [
                    c, c - ng[i-6] if i >= 6 else 0,
                    c - ng[i-12] if i >= 12 else 0,
                    np.mean(w[-72:]) if len(w) >= 72 else np.mean(w),
                    np.std(w[-72:]) if len(w) >= 72 else np.std(w),
                    np.max(w[-72:]) - np.min(w[-72:]) if len(w) >= 72 else 0,
                    np.sum((w[-72:] >= -1.5) & (w[-72:] <= 1.5)) / max(len(w[-72:]), 1) * 100,
                    np.sum(w[-72:] > 1.5) / max(len(w[-72:]), 1) * 100,
                    np.sum(w[-72:] < -1.5) / max(len(w[-72:]), 1) * 100,
                    (c - ng[i-6]) / 30 if i >= 6 else 0,
                    ((ng[i] - ng[i-6]) - (ng[i-6] - ng[i-12])) / 30 if i >= 12 else 0,
                    cv[i] if cv[i] > 0 else 0,
                    1 if cv[i] > 0 else 0,
                    iv[i] if iv[i] > 0 else 0,
                    1 if iv[i] > 0 else 0,
                    np.sin(2 * np.pi * 12 / 24),
                    np.cos(2 * np.pi * 12 / 24),
                    0, 0, 0, 0,
                ]
                features.append(feat)
                targets.append(float(ng[i + horizon] - c))
                anchors.append(gv[i])

        self.features = np.nan_to_num(np.array(features, dtype=np.float32))
        self.targets = np.nan_to_num(np.array(targets, dtype=np.float32))
        self.anchors = np.array(anchors, dtype=np.float32)

    def __len__(self):
        return len(self.features)

    def __getitem__(self, idx):
        return torch.FloatTensor(self.features[idx]), self.targets[idx]


# ==================== 训练函数 ====================

def train_one_model(model, train_loader, val_loader, val_anchors, val_targets,
                    device, epochs=50, patience=10, lr=1e-3, model_path=None):
    """训练单个模型"""
    criterion = ClarkeLoss()
    optimizer = optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingWarmRestarts(optimizer, T_0=10, T_mult=2)
    scaler = torch.amp.GradScaler('cuda')

    best_mae = float('inf')
    best_epoch = 0
    patience_counter = 0

    for epoch in range(epochs):
        t0 = time.time()
        model.train()
        total_loss = 0
        n = 0

        for bx, by in train_loader:
            bx, by = bx.to(device), by.to(device)
            with torch.amp.autocast('cuda'):
                loss = criterion(model(bx), by)
            optimizer.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            scaler.step(optimizer)
            scaler.update()
            total_loss += loss.item()
            n += 1

        # 验证
        model.eval()
        preds = []
        with torch.no_grad():
            for bx, _ in val_loader:
                preds.extend(model(bx.to(device)).cpu().numpy())
        preds = np.array(preds)
        mn = min(len(preds), len(val_anchors))
        final = val_anchors[:mn] + preds[:mn]
        actual = val_anchors[:mn] + val_targets[:mn]
        mae = np.mean(np.abs(final - actual))

        scheduler.step()

        if mae < best_mae:
            best_mae = mae
            best_epoch = epoch + 1
            patience_counter = 0
            if model_path:
                torch.save(model.state_dict(), model_path)
        else:
            patience_counter += 1
            if patience_counter >= patience:
                break

        if epoch % 5 == 0 or mae < best_mae:
            print(f"  Epoch {epoch+1:2d} ({time.time()-t0:.0f}s) | Loss: {total_loss/n:.4f} | MAE: {mae:.3f}")

    return best_mae, best_epoch


@torch.no_grad()
def ensemble_predict(models, loader, device):
    """集成预测"""
    all_preds = []
    for model in models:
        model.eval()
        preds = []
        for bx, _ in loader:
            preds.extend(model(bx.to(device)).cpu().numpy())
        all_preds.append(np.array(preds))
    return np.mean(all_preds, axis=0)


# ==================== 主函数 ====================

def main():
    data_dir = 'training/data'
    data_path = f'{data_dir}/all_data_final.csv'

    print("=" * 80)
    print("集成训练 + 预训练微调")
    print("=" * 80)

    # 读取患者
    df = pd.read_csv(data_path, usecols=['patient_id'])
    patients = df['patient_id'].unique()
    print(f"总患者: {len(patients)}")

    # 分割：60%预训练, 20%微调, 20%验证
    np.random.seed(42)
    np.random.shuffle(patients)
    n_pre = int(len(patients) * 0.6)
    n_fine = int(len(patients) * 0.8)
    pre_pids = set(patients[:n_pre])
    fine_pids = set(patients[n_pre:n_fine])
    val_pids = set(patients[n_fine:])

    print(f"预训练集: {len(pre_pids)}人")
    print(f"微调集: {len(fine_pids)}人")
    print(f"验证集: {len(val_pids)}人")

    device = torch.device('cuda')
    print(f"\nGPU: {torch.cuda.get_device_name(0)}")

    normalizer = PerPatientNormalizer()

    # ==================== 阶段1：预训练 ====================
    print(f"\n{'='*80}")
    print("阶段1：预训练（大数据集）")
    print(f"{'='*80}")

    print("加载预训练数据...")
    pre_dataset = CGMDataset(data_path, pre_pids, normalizer)
    pre_loader = DataLoader(pre_dataset, batch_size=4096, shuffle=True,
                           num_workers=4, pin_memory=True, persistent_workers=True)

    # 预训练验证集
    val_dataset = CGMDataset(data_path, val_pids, normalizer)
    val_loader = DataLoader(val_dataset, batch_size=4096,
                           num_workers=4, pin_memory=True, persistent_workers=True)

    print(f"预训练样本: {len(pre_dataset)}")
    print(f"验证样本: {len(val_dataset)}")

    # 训练3个不同种子的模型
    models = []
    for i in range(3):
        print(f"\n--- 预训练模型 {i+1}/3 ---")
        torch.manual_seed(42 + i)
        model = TCNModel(
            input_dim=pre_dataset.features.shape[1],
            channels=[64, 64, 128, 128],
            dropout=0.2 + i * 0.05,
        ).to(device)

        mae, epoch = train_one_model(
            model, pre_loader, val_loader,
            val_dataset.anchors, val_dataset.targets,
            device, epochs=30, patience=10, lr=1e-3 * (0.8**i),
            model_path=f'{data_dir}/pretrained_{i}.pt'
        )
        print(f"  最佳MAE: {mae:.3f} (Epoch {epoch})")
        models.append(model)

    # 集成评估
    preds = ensemble_predict(models, val_loader, device)
    mn = min(len(preds), len(val_dataset.anchors))
    final = val_dataset.anchors[:mn] + preds[:mn]
    actual = val_dataset.anchors[:mn] + val_dataset.targets[:mn]
    pre_mae = np.mean(np.abs(final - actual))
    print(f"\n预训练集成MAE: {pre_mae:.3f}")

    # ==================== 阶段2：微调 ====================
    print(f"\n{'='*80}")
    print("阶段2：微调（HUPA数据）")
    print(f"{'='*80}")

    # 加载HUPA数据用于微调
    hupa_path = f'{data_dir}/hupa_cgm_data.csv'
    if not os.path.exists(hupa_path):
        hupa_path = data_path

    # 获取HUPA患者ID
    hupa_df = pd.read_csv(hupa_path, usecols=['patient_id'])
    hupa_pids = hupa_df['patient_id'].unique()

    # 分割HUPA患者：80%微调，20%验证
    np.random.seed(42)
    np.random.shuffle(hupa_pids)
    n_fine_hupa = int(len(hupa_pids) * 0.8)
    fine_hupa_pids = set(hupa_pids[:n_fine_hupa])
    val_hupa_pids = set(hupa_pids[n_fine_hupa:])

    print(f"HUPA患者: {len(hupa_pids)}人")
    print(f"微调集: {len(fine_hupa_pids)}人")
    print(f"验证集: {len(val_hupa_pids)}人")

    print("加载微调数据...")
    fine_dataset = CGMDataset(hupa_path, fine_hupa_pids, normalizer)
    fine_loader = DataLoader(fine_dataset, batch_size=2048, shuffle=True,
                            num_workers=4, pin_memory=True, persistent_workers=True)

    val_hupa_dataset = CGMDataset(hupa_path, val_hupa_pids, normalizer)
    val_hupa_loader = DataLoader(val_hupa_dataset, batch_size=2048,
                                num_workers=4, pin_memory=True, persistent_workers=True)

    print(f"微调样本: {len(fine_dataset)}")
    print(f"验证样本: {len(val_hupa_dataset)}")

    # 微调每个模型
    finetuned_models = []
    for i, model in enumerate(models):
        print(f"\n--- 微调模型 {i+1}/3 ---")

        # 加载预训练权重
        model.load_state_dict(torch.load(f'{data_dir}/pretrained_{i}.pt'))

        # 冻结底层，只微调顶层
        for name, param in model.named_parameters():
            if 'network.0' in name or 'network.1' in name:
                param.requires_grad = False

        mae, epoch = train_one_model(
            model, fine_loader, val_hupa_loader,
            val_hupa_dataset.anchors, val_hupa_dataset.targets,
            device, epochs=20, patience=8, lr=1e-4,
            model_path=f'{data_dir}/finetuned_{i}.pt'
        )
        print(f"  最佳MAE: {mae:.3f} (Epoch {epoch})")
        finetuned_models.append(model)

    # ==================== 最终评估 ====================
    print(f"\n{'='*80}")
    print("最终评估")
    print(f"{'='*80}")

    # 加载最佳权重
    for i, model in enumerate(finetuned_models):
        model.load_state_dict(torch.load(f'{data_dir}/finetuned_{i}.pt'))

    # 集成预测
    preds = ensemble_predict(finetuned_models, val_loader, device)
    mn = min(len(preds), len(val_dataset.anchors))
    final = val_dataset.anchors[:mn] + preds[:mn]
    actual = val_dataset.anchors[:mn] + val_dataset.targets[:mn]

    mae = np.mean(np.abs(final - actual))
    rmse = np.sqrt(np.mean((final - actual)**2))
    clarke_a = sum(1 for p, a in zip(final, actual)
                   if (a < 5.6 and abs(p-a) <= 1.4) or (a >= 5.6 and 0.8*a <= p <= 1.2*a))

    print(f"集成MAE: {mae:.3f} mmol/L")
    print(f"集成RMSE: {rmse:.3f} mmol/L")
    print(f"集成Clarke A: {clarke_a/mn*100:.1f}%")

    # 保存结果
    results = {
        'method': 'ensemble_pretrain_finetune',
        'n_models': 3,
        'total_patients': len(patients),
        'pretrain_mae': float(pre_mae),
        'final_mae': float(mae),
        'final_rmse': float(rmse),
        'clarke_a': float(clarke_a / mn * 100),
    }
    with open(f'{data_dir}/results_ensemble.json', 'w') as f:
        json.dump(results, f, indent=2)

    print(f"\n{'='*80}")
    print("完成!")
    print(f"{'='*80}")


if __name__ == '__main__':
    main()
