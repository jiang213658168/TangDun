# backend/app/algorithms/bergman/parameters.py
# Bergman模型参数定义

from dataclasses import dataclass
from typing import Tuple


@dataclass
class BergmanParameters:
    """改良Bergman最小模型参数

    基于经典Bergman最小模型(1979年)进行改良，加入饮食输入和运动消耗项
    """

    # 葡萄糖自调节速率常数 (min^-1)
    p1: float = 0.03

    # 胰岛素作用衰减速率常数 (min^-1)
    p2: float = 0.02

    # 胰岛素促进葡萄糖摄取的速率常数
    p3: float = 0.00001

    # 胰岛素清除速率常数 (min^-1)
    n: float = 0.26

    # 胰岛素分泌的葡萄糖刺激系数
    gamma: float = 0.0041

    # 基础血糖浓度 (mmol/L)
    Gb: float = 5.0

    # 基础胰岛素浓度 (mU/L)
    Ib: float = 10.0

    # 葡萄糖分布容积 (L/kg)
    Vg: float = 0.16

    # 碳水吸收速率常数 (min^-1)
    k_abs: float = 0.05

    # 快速吸收速率常数 (min^-1)
    k_fast: float = 0.2

    # 参数边界
    bounds: Tuple[list, list] = None

    def __post_init__(self):
        """初始化参数边界"""
        self.bounds = (
            [0.001, 0.001, 1e-7, 0.05, 0.001],  # 下界
            [0.1, 0.1, 1e-3, 0.5, 0.01]          # 上界
        )

    def to_array(self) -> 'np.ndarray':
        """转换为numpy数组"""
        import numpy as np
        return np.array([self.p1, self.p2, self.p3, self.n, self.gamma])

    @classmethod
    def from_array(cls, params: 'np.ndarray') -> 'BergmanParameters':
        """从numpy数组创建参数"""
        return cls(
            p1=params[0],
            p2=params[1],
            p3=params[2],
            n=params[3],
            gamma=params[4]
        )

    def to_dict(self) -> dict:
        """转换为字典"""
        return {
            'p1': self.p1,
            'p2': self.p2,
            'p3': self.p3,
            'n': self.n,
            'gamma': self.gamma,
            'Gb': self.Gb,
            'Ib': self.Ib,
            'Vg': self.Vg,
            'k_abs': self.k_abs,
            'k_fast': self.k_fast,
        }

    @classmethod
    def from_dict(cls, data: dict) -> 'BergmanParameters':
        """从字典创建参数"""
        return cls(
            p1=data.get('p1', 0.03),
            p2=data.get('p2', 0.02),
            p3=data.get('p3', 0.00001),
            n=data.get('n', 0.26),
            gamma=data.get('gamma', 0.0041),
            Gb=data.get('Gb', 5.0),
            Ib=data.get('Ib', 10.0),
            Vg=data.get('Vg', 0.16),
            k_abs=data.get('k_abs', 0.05),
            k_fast=data.get('k_fast', 0.2),
        )
