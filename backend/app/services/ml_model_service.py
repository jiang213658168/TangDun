# backend/app/services/ml_model_service.py
# 机器学习模型服务 - TCN连续曲线预测
#
# 模型: TCN (Temporal Convolutional Network)
# 特征: 15维 (血糖动态9维 + 胰岛素2维 + 碳水2维 + 心率1维 + 步数1维)
# 输出: 4个曲线参数 → 生成0-120分钟血糖曲线
# 性能: MAE 0.552 mmol/L, Clarke A 92.4%

import torch
import torch.nn as nn
import numpy as np
import os
import json
from datetime import datetime, timedelta
from typing import Dict, List, Optional

import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# ==================== TCN模型架构（与训练脚本一致） ====================

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
    """TCN曲线预测模型（与训练脚本完全一致）"""

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


# ==================== 模型服务 ====================

class MLModelService:
    """TCN机器学习模型服务

    特征维度: 15维
    - 血糖动态: 9维 (当前值、5/15/30/60min变化、30/60min ROC、6h均值/标准差)
    - 胰岛素: 2维 (4h总量、最近注射时间)
    - 碳水: 2维 (4h总量、最近进食时间)
    - 心率: 1维 (1h平均)
    - 步数: 1维 (1h累计)
    """

    def __init__(self):
        self.model = None
        self.device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
        self.is_loaded = False

        # 模型路径（使用新训练的TCN模型）
        self.model_dir = os.path.join(os.path.dirname(__file__), '..', '..', 'training', 'data')
        self.model_path = os.path.join(self.model_dir, 'model_curve_v2.pt')

        # 患者历史数据缓存（用于提取胰岛素/碳水/心率/步数特征）
        self._patient_data = {}

    def load_model(self) -> bool:
        """加载训练好的TCN模型"""
        if not os.path.exists(self.model_path):
            print(f"模型文件不存在: {self.model_path}")
            return False

        try:
            # 使用 weights_only=False 因为模型文件包含numpy对象（我们自己训练的可信文件）
            checkpoint = torch.load(self.model_path, map_location=self.device, weights_only=False)

            self.model = TCNCurvePredictor(
                input_dim=checkpoint.get('input_dim', 15),
                channels=checkpoint.get('channels', [64, 64, 128]),
                dropout=0.0,  # 推理时不用dropout
            )
            self.model.load_state_dict(checkpoint['model_state_dict'])
            self.model.to(self.device)
            self.model.eval()

            self.is_loaded = True
            print(f"TCN模型加载成功: {self.model_path}")
            print(f"  输入维度: {checkpoint.get('input_dim', 15)}")
            print(f"  最佳MAE: {checkpoint.get('best_mae', 'N/A')}")
            return True

        except Exception as e:
            print(f"模型加载失败: {e}")
            return False

    def set_patient_data(self, patient_id: int, data: Dict):
        """设置患者历史数据（胰岛素、碳水、心率、步数）"""
        self._patient_data[patient_id] = data

    def extract_features(self, glucose_history: np.ndarray,
                         patient_id: Optional[int] = None) -> np.ndarray:
        """提取15维特征（与训练脚本完全一致）

        Args:
            glucose_history: 血糖历史 (至少288点 = 24小时)
            patient_id: 患者ID（用于获取胰岛素/碳水/心率/步数数据）
        """
        idx = len(glucose_history) - 1

        # 计算统计量（24h滑动窗口）
        start = max(0, idx - 288)
        history = glucose_history[start:idx]

        if len(history) < 10:
            history = glucose_history[:idx] if idx > 0 else np.array([8.0])

        mean = np.mean(history)
        std = np.std(history) if np.std(history) > 0 else 1.0

        # === 血糖特征 (1-9) ===
        # 1: 当前血糖值（归一化）
        f1 = (glucose_history[idx] - mean) / std

        # 2-5: 变化量
        f2 = (glucose_history[idx] - glucose_history[idx-1]) / std if idx >= 1 else 0
        f3 = (glucose_history[idx] - glucose_history[idx-3]) / std if idx >= 3 else 0
        f4 = (glucose_history[idx] - glucose_history[idx-6]) / std if idx >= 6 else 0
        f5 = (glucose_history[idx] - glucose_history[idx-12]) / std if idx >= 12 else 0

        # 6-7: ROC
        f6 = f4 / 30.0
        f7 = f5 / 60.0

        # 8-9: 统计特征
        recent = history[-72:] if len(history) >= 72 else history
        f8 = (np.mean(recent) - mean) / std
        f9 = np.std(recent) / std

        # === 胰岛素/碳水/心率/步数特征 (10-15) ===
        patient_data = self._patient_data.get(patient_id, {})

        # 胰岛素
        bolus_values = patient_data.get('bolus', np.zeros(288))
        f10 = np.sum(bolus_values[max(0, idx-48):idx+1])  # 4h总量
        nonzero = np.nonzero(bolus_values[max(0, idx-144):idx+1])[0]
        f11 = (len(bolus_values[max(0, idx-144):idx+1]) - nonzero[-1]) * 5 if len(nonzero) > 0 else 999

        # 碳水
        carb_values = patient_data.get('carbs', np.zeros(288))
        f12 = np.sum(carb_values[max(0, idx-48):idx+1])  # 4h总量
        nonzero = np.nonzero(carb_values[max(0, idx-144):idx+1])[0]
        f13 = (len(carb_values[max(0, idx-144):idx+1]) - nonzero[-1]) * 5 if len(nonzero) > 0 else 999

        # 心率
        hr_values = patient_data.get('heart_rate', np.zeros(288))
        hr_recent = hr_values[max(0, idx-12):idx+1]
        hr_valid = hr_recent[hr_recent > 0]
        f14 = np.mean(hr_valid) if len(hr_valid) > 0 else 0.0

        # 步数
        step_values = patient_data.get('steps', np.zeros(288))
        f15 = np.sum(step_values[max(0, idx-12):idx+1])

        return np.array([f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15],
                        dtype=np.float32)

    def predict(self, glucose_history: np.ndarray, current_glucose: float,
                patient_id: Optional[int] = None) -> Dict:
        """使用TCN模型预测

        Args:
            glucose_history: 最近血糖历史 (mmol/L)
            current_glucose: 当前血糖值 (mmol/L)
            patient_id: 患者ID（用于获取额外特征）

        Returns:
            预测结果，包含0-120分钟曲线数据
        """
        if not self.is_loaded:
            return self._fallback_prediction(current_glucose)

        try:
            # 提取15维特征
            features = self.extract_features(glucose_history, patient_id)

            # 转换为张量
            x = torch.FloatTensor(features).unsqueeze(0).to(self.device)

            # 模型预测（4个曲线参数）
            with torch.no_grad():
                params = self.model(x).cpu().numpy()[0]

            # 生成曲线 (25个点，0-120分钟，每5分钟一个)
            curve = self._generate_curve(params, current_glucose, num_points=25)

            # 关键时间点
            predicted_5min = curve[1] if len(curve) > 1 else current_glucose
            predicted_15min = curve[3] if len(curve) > 3 else current_glucose
            predicted_30min = curve[6] if len(curve) > 6 else current_glucose
            predicted_60min = curve[12] if len(curve) > 12 else current_glucose
            predicted_120min = curve[24] if len(curve) > 24 else current_glucose

            # 风险等级（基于30分钟预测）
            if predicted_30min < 3.9:
                risk_level = "low_risk"
            elif predicted_30min > 10.0:
                risk_level = "high_risk"
            else:
                risk_level = "normal"

            return {
                'value': round(float(predicted_30min), 1),
                'predicted_5min': round(float(predicted_5min), 1),
                'predicted_15min': round(float(predicted_15min), 1),
                'predicted_30min': round(float(predicted_30min), 1),
                'predicted_60min': round(float(predicted_60min), 1),
                'predicted_120min': round(float(predicted_120min), 1),
                'upper': round(float(max(curve)), 1),
                'lower': round(float(min(curve)), 1),
                'curve': [round(float(v), 1) for v in curve],
                'risk_level': risk_level,
                'model_type': 'tcn_v2',
            }

        except Exception as e:
            print(f"模型预测失败: {e}")
            import traceback
            traceback.print_exc()
            return self._fallback_prediction(current_glucose)

    def _generate_curve(self, params: np.ndarray, current_value: float,
                         num_points: int = 25) -> np.ndarray:
        """生成连续曲线（25个点，0-120分钟）

        G(t) = current * (1 + a*t³ + b*t² + c*t + d)
        """
        t = np.linspace(0, 1, num_points)
        a, b, c, d = params

        relative_change = a * t**3 + b * t**2 + c * t + d
        curve = current_value * (1 + relative_change)

        return curve

    def _fallback_prediction(self, current_glucose: float) -> Dict:
        """备用预测（模型未加载时）"""
        return {
            'value': round(float(current_glucose), 1),
            'predicted_5min': round(float(current_glucose), 1),
            'predicted_15min': round(float(current_glucose), 1),
            'predicted_30min': round(float(current_glucose), 1),
            'predicted_60min': round(float(current_glucose), 1),
            'predicted_120min': round(float(current_glucose), 1),
            'upper': round(float(current_glucose + 2.0), 1),
            'lower': round(float(current_glucose - 2.0), 1),
            'curve': [round(float(current_glucose), 1)] * 25,
            'risk_level': 'normal',
            'model_type': 'fallback',
        }


# 全局实例
ml_model_service = MLModelService()
