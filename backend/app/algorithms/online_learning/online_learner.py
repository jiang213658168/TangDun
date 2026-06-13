# backend/app/algorithms/online_learning/online_learner.py
# 个人化在线学习模块
# 使用PyTorch实现真正的梯度更新

import torch
import torch.nn as nn
import numpy as np
from typing import Dict, List, Optional, Tuple
from collections import deque


class OnlineLearner:
    """个人化在线学习器

    使模型能够从用户真实数据中持续学习，越用越准

    学习策略:
    - 初始阶段 (数据量<3天): 使用通用模型，不进行个人化调整
    - 冷启动阶段 (数据量3-14天): 微调模型最后2层，学习率1e-4，EWC正则化
    - 稳定阶段 (数据量>14天): 全模型微调，学习率5e-5，EWC+经验回放
    """

    def __init__(self, model: nn.Module, buffer_size: int = 10000):
        """初始化在线学习器

        Args:
            model: PyTorch模型
            buffer_size: 经验回放缓冲区大小
        """
        self.model = model
        self.buffer_size = buffer_size
        self.device = next(model.parameters()).device

        # 经验回放缓冲区
        self.replay_buffer = deque(maxlen=buffer_size)

        # 学习阶段
        self.stage = 'initial'  # initial/cold_start/stable

        # 训练统计
        self.training_samples = 0
        self.update_count = 0

        # EWC相关
        self.fisher_matrix = None
        self.optimal_params = None
        self.ewc_lambda = 1000

        # 学习率
        self.learning_rate = 1e-4

        # 更新频率
        self.update_frequency = 50  # 每积累50条新数据触发一次
        self.update_steps = 10  # 每次训练10步

        # 优化器
        self.optimizer = None

    def determine_stage(self, data_days: float):
        """确定学习阶段

        Args:
            data_days: 数据天数
        """
        if data_days < 3:
            self.stage = 'initial'
            self.learning_rate = 0
        elif data_days < 14:
            self.stage = 'cold_start'
            self.learning_rate = 1e-4
        else:
            self.stage = 'stable'
            self.learning_rate = 5e-5

        # 更新优化器
        if self.learning_rate > 0:
            self.optimizer = torch.optim.Adam(
                self.model.parameters(), lr=self.learning_rate
            )

    def add_experience(self, features: np.ndarray, target: float):
        """添加经验到回放缓冲区

        Args:
            features: 特征向量
            target: 目标值 (delta)
        """
        self.replay_buffer.append((features, target))
        self.training_samples += 1

        # 检查是否需要更新
        if self.training_samples % self.update_frequency == 0 and self.stage != 'initial':
            self.update()

    def update(self):
        """执行在线更新"""
        if self.stage == 'initial' or self.optimizer is None:
            return

        if len(self.replay_buffer) < 100:
            return

        # 采样新数据和回放数据
        new_data_ratio = 0.7
        n_new = int(self.update_steps * new_data_ratio)
        n_replay = self.update_steps - n_new

        # 从缓冲区采样
        buffer_list = list(self.replay_buffer)
        new_samples = buffer_list[-n_new:] if len(buffer_list) >= n_new else buffer_list

        replay_indices = np.random.choice(
            len(buffer_list), size=min(n_replay, len(buffer_list)), replace=False
        )
        replay_samples = [buffer_list[i] for i in replay_indices]

        # 合并数据
        all_samples = new_samples + replay_samples

        # 执行更新
        self.model.train()
        for features, target in all_samples:
            self._update_step(features, target)

        self.model.eval()
        self.update_count += 1

    def _update_step(self, features: np.ndarray, target: float):
        """单步更新

        Args:
            features: 特征向量
            target: 目标值 (delta)
        """
        # 转换为张量
        x = torch.FloatTensor(features).unsqueeze(0).to(self.device)
        y = torch.FloatTensor([target]).to(self.device)

        # 前向传播
        output = self.model(x)
        delta_pred = output['delta']

        # 计算损失
        loss = nn.MSELoss()(delta_pred, y)

        # EWC正则化
        if self.fisher_matrix is not None and self.optimal_params is not None:
            ewc_penalty = self._compute_ewc_penalty()
            loss += ewc_penalty

        # 反向传播
        self.optimizer.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(self.model.parameters(), max_norm=1.0)
        self.optimizer.step()

    def _compute_ewc_penalty(self) -> torch.Tensor:
        """计算EWC正则化惩罚

        Returns:
            EWC惩罚值
        """
        penalty = torch.tensor(0.0, device=self.device)

        for name, param in self.model.named_parameters():
            if name in self.fisher_matrix and name in self.optimal_params:
                fisher = self.fisher_matrix[name]
                optimal = self.optimal_params[name]
                penalty += (fisher * (param - optimal) ** 2).sum()

        return 0.5 * self.ewc_lambda * penalty

    def compute_fisher_matrix(self, dataloader):
        """计算Fisher信息矩阵

        Args:
            dataloader: 数据加载器
        """
        self.model.eval()
        self.fisher_matrix = {}
        self.optimal_params = {}

        # 保存当前最优参数
        for name, param in self.model.named_parameters():
            self.optimal_params[name] = param.data.clone()

        # 计算Fisher信息
        fisher_counts = {name: 0 for name in self.model.named_parameters()}

        for batch_x, batch_y in dataloader:
            batch_x = batch_x.to(self.device)
            batch_y = batch_y.to(self.device)

            self.model.zero_grad()
            output = self.model(batch_x)
            loss = nn.MSELoss()(output['delta'], batch_y)
            loss.backward()

            for name, param in self.model.named_parameters():
                if param.grad is not None:
                    if name not in self.fisher_matrix:
                        self.fisher_matrix[name] = param.grad.data.clone() ** 2
                    else:
                        self.fisher_matrix[name] += param.grad.data.clone() ** 2
                    fisher_counts[name] += 1

        # 平均
        for name in self.fisher_matrix:
            if fisher_counts[name] > 0:
                self.fisher_matrix[name] /= fisher_counts[name]

    def get_training_info(self) -> Dict:
        """获取训练信息

        Returns:
            训练信息字典
        """
        return {
            'stage': self.stage,
            'training_samples': self.training_samples,
            'update_count': self.update_count,
            'buffer_size': len(self.replay_buffer),
            'learning_rate': self.learning_rate,
            'has_fisher_matrix': self.fisher_matrix is not None,
        }

    def save_state(self, filepath: str):
        """保存学习状态

        Args:
            filepath: 保存路径
        """
        import json
        state = {
            'stage': self.stage,
            'training_samples': self.training_samples,
            'update_count': self.update_count,
            'learning_rate': self.learning_rate,
            'buffer_size': len(self.replay_buffer),
        }
        with open(filepath, 'w') as f:
            json.dump(state, f)

    def load_state(self, filepath: str):
        """加载学习状态

        Args:
            filepath: 加载路径
        """
        import json
        with open(filepath, 'r') as f:
            state = json.load(f)

        self.stage = state['stage']
        self.training_samples = state['training_samples']
        self.update_count = state['update_count']
        self.learning_rate = state['learning_rate']
