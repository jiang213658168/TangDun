# backend/app/services/personalization_service.py
# 个人化校准服务 - 真正的PyTorch模型微调

import torch
import torch.nn as nn
import torch.optim as optim
import numpy as np
import os
import json
from datetime import datetime, timedelta
from typing import Dict, List, Optional
from sqlalchemy.orm import Session

from app.models.glucose import GlucoseRecord
from app.models.user import User


# ==================== 按患者归一化 ====================

class PerPatientNormalizer:
    """按患者归一化"""

    def __init__(self):
        self.mean = 8.0
        self.std = 3.0

    def fit(self, values: np.ndarray):
        self.mean = float(np.mean(values))
        self.std = float(np.std(values)) if np.std(values) > 0 else 1.0

    def transform(self, values: np.ndarray) -> np.ndarray:
        return (values - self.mean) / self.std

    def inverse_transform(self, values: np.ndarray) -> np.ndarray:
        return values * self.std + self.mean

    def to_dict(self) -> Dict:
        return {'mean': self.mean, 'std': self.std}

    def from_dict(self, data: Dict):
        self.mean = data['mean']
        self.std = data['std']


# ==================== TCN模型（与训练代码一致） ====================

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
    """TCN模型（与训练代码完全一致）"""

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


# ==================== 个人化服务 ====================

class PersonalizationService:
    """个人化校准服务 - 真正的PyTorch模型微调"""

    def __init__(self):
        self.model_dir = os.path.join(os.path.dirname(__file__), '..', '..', 'training', 'data')
        self.user_models_dir = os.path.join(os.path.dirname(__file__), '..', '..', 'data', 'user_models')
        os.makedirs(self.user_models_dir, exist_ok=True)

        # 通用模型路径
        self.base_model_path = os.path.join(self.model_dir, 'model_improved.pt')

    def _load_base_model(self) -> TCNModel:
        """加载通用模型"""
        model = TCNModel(input_dim=21, channels=[64, 64, 128, 128], dropout=0.2)

        if os.path.exists(self.base_model_path):
            model.load_state_dict(torch.load(self.base_model_path, map_location='cpu'))
            print(f"已加载通用模型")
        else:
            print(f"通用模型不存在，使用随机初始化")

        return model

    def get_user_stage(self, user_id: int, db: Session) -> Dict:
        """获取用户当前的学习阶段"""
        record_count = db.query(GlucoseRecord).filter(
            GlucoseRecord.user_id == user_id
        ).count()

        first_record = (
            db.query(GlucoseRecord)
            .filter(GlucoseRecord.user_id == user_id)
            .order_by(GlucoseRecord.timestamp.asc())
            .first()
        )

        if first_record is None:
            return {
                'stage': 'initial',
                'data_days': 0,
                'record_count': 0,
                'message': '暂无数据，请先连接CGM设备',
            }

        data_days = (datetime.now() - first_record.timestamp).days + 1

        if data_days < 3:
            stage = 'initial'
            message = f'已收集{data_days}天数据，使用通用模型'
        elif data_days < 14:
            stage = 'cold_start'
            message = f'已收集{data_days}天数据，正在学习您的血糖模式'
        else:
            stage = 'stable'
            message = f'已收集{data_days}天数据，可以校准个人化模型'

        has_personal_model = os.path.exists(
            os.path.join(self.user_models_dir, f'user_{user_id}_model.pt')
        )

        return {
            'stage': stage,
            'data_days': data_days,
            'record_count': record_count,
            'has_personal_model': has_personal_model,
            'message': message,
        }

    def calibrate_for_user(self, user_id: int, db: Session) -> Dict:
        """为用户校准模型（真正的PyTorch微调）"""
        # 获取用户数据
        records = (
            db.query(GlucoseRecord)
            .filter(GlucoseRecord.user_id == user_id)
            .order_by(GlucoseRecord.timestamp.asc())
            .all()
        )

        if len(records) < 288:  # 至少1天数据
            return {
                'success': False,
                'message': f'数据不足，需要至少288条记录（约1天），当前{len(records)}条',
            }

        # 提取血糖值
        glucose_values = np.array([r.value for r in records])

        # 计算用户特异性统计量
        normalizer = PerPatientNormalizer()
        normalizer.fit(glucose_values)

        # 提取特征和目标
        features, targets = self._extract_features(glucose_values, normalizer)

        if len(features) < 100:
            return {
                'success': False,
                'message': f'有效样本不足，需要至少100个，当前{len(features)}个',
            }

        # 微调模型
        finetune_result = self._finetune_model(user_id, features, targets)

        # 保存用户配置
        user_config = {
            'user_id': user_id,
            'calibration_time': datetime.now().isoformat(),
            'data_count': len(records),
            'data_days': (records[-1].timestamp - records[0].timestamp).days + 1,
            'normalizer': normalizer.to_dict(),
            'glucose_stats': {
                'mean': float(np.mean(glucose_values)),
                'std': float(np.std(glucose_values)),
                'min': float(np.min(glucose_values)),
                'max': float(np.max(glucose_values)),
            },
            'finetune_result': finetune_result,
        }

        config_path = os.path.join(self.user_models_dir, f'user_{user_id}_config.json')
        with open(config_path, 'w') as f:
            json.dump(user_config, f, indent=2, ensure_ascii=False)

        return {
            'success': True,
            'message': '个人化校准完成',
            'config': user_config,
        }

    def _extract_features(self, glucose_values: np.ndarray,
                           normalizer: PerPatientNormalizer) -> tuple:
        """提取特征和目标"""
        normalized = normalizer.transform(glucose_values)

        features = []
        targets = []
        window = 288
        horizon = 12

        for i in range(window, len(normalized) - horizon):
            w = normalized[i-window:i]
            c = normalized[i]

            # 21维特征（与训练代码一致）
            feat = [
                c,                                                          # 0: 当前值
                c - normalized[i-6] if i >= 6 else 0,                      # 1: 30分钟变化
                c - normalized[i-12] if i >= 12 else 0,                    # 2: 60分钟变化
                np.mean(w[-72:]) if len(w) >= 72 else np.mean(w),          # 3: 6h均值
                np.std(w[-72:]) if len(w) >= 72 else np.std(w),            # 4: 6h标准差
                np.max(w[-72:]) - np.min(w[-72:]) if len(w) >= 72 else 0,  # 5: 6h极差
                np.sum((w[-72:] >= -1.5) & (w[-72:] <= 1.5)) / max(len(w[-72:]), 1) * 100,  # 6: TIR
                np.sum(w[-72:] > 1.5) / max(len(w[-72:]), 1) * 100,       # 7: TAR
                np.sum(w[-72:] < -1.5) / max(len(w[-72:]), 1) * 100,      # 8: TBR
                (c - normalized[i-6]) / 30 if i >= 6 else 0,               # 9: 变化率
                ((normalized[i] - normalized[i-6]) - (normalized[i-6] - normalized[i-12])) / 30 if i >= 12 else 0,  # 10: 加速度
                0, 0, 0, 0,  # 11-14: 碳水/胰岛素（占位）
                np.sin(2 * np.pi * 12 / 24),  # 15: 时间sin
                np.cos(2 * np.pi * 12 / 24),  # 16: 时间cos
                0, 0, 0, 0,  # 17-20: 其他时序特征
            ]

            target = float(normalized[i + horizon] - c)
            features.append(feat)
            targets.append(target)

        return np.array(features, dtype=np.float32), np.array(targets, dtype=np.float32)

    def _finetune_model(self, user_id: int, features: np.ndarray,
                         targets: np.ndarray) -> Dict:
        """微调模型（真正的PyTorch训练）"""
        # 加载通用模型
        model = self._load_base_model()

        # 冻结前两层，只微调后两层和输出层
        for name, param in model.named_parameters():
            if 'network.0' in name or 'network.1' in name:
                param.requires_grad = False

        # 设备
        device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
        model = model.to(device)

        # 转换数据
        X = torch.FloatTensor(features).to(device)
        y = torch.FloatTensor(targets).to(device)

        # 优化器（只优化可训练参数）
        optimizer = optim.Adam(
            filter(lambda p: p.requires_grad, model.parameters()),
            lr=1e-4,
            weight_decay=1e-5
        )
        criterion = nn.HuberLoss(delta=1.0)

        # 训练
        best_loss = float('inf')
        best_state = None
        patience = 5
        patience_counter = 0

        for epoch in range(30):
            model.train()

            # 前向传播
            pred = model(X)
            loss = criterion(pred, y)

            # 反向传播
            optimizer.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()

            loss_val = loss.item()

            if loss_val < best_loss:
                best_loss = loss_val
                best_state = {k: v.cpu().clone() for k, v in model.state_dict().items()}
                patience_counter = 0
            else:
                patience_counter += 1
                if patience_counter >= patience:
                    break

        # 加载最佳权重
        if best_state is not None:
            model.load_state_dict(best_state)

        # 保存个人化模型
        model_path = os.path.join(self.user_models_dir, f'user_{user_id}_model.pt')
        torch.save(model.state_dict(), model_path)

        # 评估
        model.eval()
        with torch.no_grad():
            pred = model(X).cpu().numpy()
        mae = np.mean(np.abs(pred - targets))

        return {
            'epochs': epoch + 1,
            'best_loss': float(best_loss),
            'mae': float(mae),
            'samples': len(features),
            'model_path': model_path,
        }

    def predict_for_user(self, user_id: int, glucose_history: np.ndarray,
                          current_glucose: float,
                          carbs: float = 0, insulin: float = 0) -> Dict:
        """使用个人化模型预测

        Args:
            user_id: 用户ID
            glucose_history: 最近24小时血糖历史 (288个点)
            current_glucose: 当前血糖值
            carbs: 最近碳水摄入 (g)
            insulin: 最近胰岛素剂量 (U)

        Returns:
            预测结果
        """
        device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')

        # 检查是否有个人化模型
        model_path = os.path.join(self.user_models_dir, f'user_{user_id}_model.pt')
        config_path = os.path.join(self.user_models_dir, f'user_{user_id}_config.json')

        if os.path.exists(model_path) and os.path.exists(config_path):
            # 加载个人化模型
            model = TCNModel(input_dim=21, channels=[64, 64, 128, 128], dropout=0.2)
            model.load_state_dict(torch.load(model_path, map_location=device))
            model = model.to(device)
            model.eval()

            # 加载用户归一化参数
            with open(config_path, 'r') as f:
                config = json.load(f)
            normalizer = PerPatientNormalizer()
            normalizer.from_dict(config['normalizer'])

            # 归一化血糖历史
            normalized_history = normalizer.transform(glucose_history)

            # 构建完整21维特征
            features = self._build_features(normalized_history, carbs, insulin)

            # 预测
            with torch.no_grad():
                delta = model(torch.FloatTensor(features).unsqueeze(0).to(device)).item()

            # 反归一化预测的变化量
            predicted_glucose = current_glucose + delta * normalizer.std

            return {
                'predicted_glucose': round(float(predicted_glucose), 1),
                'model': 'personalized',
                'user_mean': round(normalizer.mean, 1),
                'user_std': round(normalizer.std, 1),
            }
        else:
            # 使用通用模型
            return {
                'predicted_glucose': round(float(current_glucose), 1),
                'model': 'general',
                'message': '个人化模型未建立，请先校准',
            }

    def _build_features(self, normalized_history: np.ndarray,
                         carbs: float, insulin: float) -> np.ndarray:
        """构建完整21维特征

        Args:
            normalized_history: 归一化后的血糖历史
            carbs: 碳水摄入 (g)
            insulin: 胰岛素剂量 (U)

        Returns:
            21维特征向量
        """
        c = normalized_history[-1]
        w = normalized_history[-288:] if len(normalized_history) >= 288 else normalized_history

        features = np.array([
            c,                                                          # 0: 当前值
            c - normalized_history[-6] if len(normalized_history) >= 6 else 0,    # 1: 30分钟变化
            c - normalized_history[-12] if len(normalized_history) >= 12 else 0,  # 2: 60分钟变化
            np.mean(w[-72:]) if len(w) >= 72 else np.mean(w),          # 3: 6h均值
            np.std(w[-72:]) if len(w) >= 72 else np.std(w),            # 4: 6h标准差
            np.max(w[-72:]) - np.min(w[-72:]) if len(w) >= 72 else 0,  # 5: 6h极差
            np.sum((w[-72:] >= -1.5) & (w[-72:] <= 1.5)) / max(len(w[-72:]), 1) * 100,  # 6: TIR
            np.sum(w[-72:] > 1.5) / max(len(w[-72:]), 1) * 100,       # 7: TAR
            np.sum(w[-72:] < -1.5) / max(len(w[-72:]), 1) * 100,      # 8: TBR
            (c - normalized_history[-6]) / 30 if len(normalized_history) >= 6 else 0,    # 9: 变化率
            ((normalized_history[-1] - normalized_history[-6]) - (normalized_history[-6] - normalized_history[-12])) / 30 if len(normalized_history) >= 12 else 0,  # 10: 加速度
            carbs if carbs > 0 else 0,                                  # 11: 碳水
            1 if carbs > 0 else 0,                                      # 12: 碳水掩码
            insulin if insulin > 0 else 0,                              # 13: 胰岛素
            1 if insulin > 0 else 0,                                    # 14: 胰岛素掩码
            np.sin(2 * np.pi * datetime.now().hour / 24),               # 15: 时间sin
            np.cos(2 * np.pi * datetime.now().hour / 24),               # 16: 时间cos
            1 if 6 <= datetime.now().hour <= 9 else 0,                  # 17: 早餐时间
            1 if 11 <= datetime.now().hour <= 13 else 0,                # 18: 午餐时间
            1 if 17 <= datetime.now().hour <= 19 else 0,                # 19: 晚餐时间
            1 if 0 <= datetime.now().hour <= 6 else 0,                  # 20: 夜间
        ], dtype=np.float32)

        return features


# 全局实例
personalization_service = PersonalizationService()
