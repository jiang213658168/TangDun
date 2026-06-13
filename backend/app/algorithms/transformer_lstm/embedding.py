# backend/app/algorithms/transformer_lstm/embedding.py
# 特征嵌入层实现

import numpy as np


class FeatureEmbedding:
    """特征嵌入层

    将不同维度的特征映射到统一的d_model维度
    """

    def __init__(self, input_dims: dict, d_model: int):
        """初始化特征嵌入层

        Args:
            input_dims: 各特征类型的输入维度
                {
                    'glucose': 22,
                    'meal': 8,
                    'exercise': 7,
                    'temporal': 4
                }
            d_model: 模型维度
        """
        self.input_dims = input_dims
        self.d_model = d_model

        # 为每种特征类型创建嵌入矩阵
        self.embeddings = {}
        for name, dim in input_dims.items():
            scale = np.sqrt(2.0 / dim)
            self.embeddings[name] = np.random.randn(dim, d_model) * scale

    def embed(self, features: dict) -> np.ndarray:
        """将特征嵌入到统一维度

        Args:
            features: 特征字典
                {
                    'glucose': np.ndarray (22,),
                    'meal': np.ndarray (8,),
                    'exercise': np.ndarray (7,),
                    'temporal': np.ndarray (4,)
                }

        Returns:
            嵌入后的特征 (d_model,)
        """
        embedded = np.zeros(self.d_model)

        for name, feature in features.items():
            if name in self.embeddings:
                # 线性变换
                emb = np.matmul(feature, self.embeddings[name])
                # 残差连接
                embedded += emb

        return embedded

    def embed_sequence(self, feature_sequence: list) -> np.ndarray:
        """嵌入特征序列

        Args:
            feature_sequence: 特征字典列表

        Returns:
            嵌入后的序列 (seq_len, d_model)
        """
        seq_len = len(feature_sequence)
        embedded = np.zeros((seq_len, self.d_model))

        for i, features in enumerate(feature_sequence):
            embedded[i] = self.embed(features)

        return embedded

    def save(self, filepath: str):
        """保存嵌入层参数

        Args:
            filepath: 保存路径
        """
        import json
        data = {
            'input_dims': self.input_dims,
            'd_model': self.d_model,
            'embeddings': {
                name: emb.tolist() for name, emb in self.embeddings.items()
            }
        }
        with open(filepath, 'w') as f:
            json.dump(data, f)

    def load(self, filepath: str):
        """加载嵌入层参数

        Args:
            filepath: 加载路径
        """
        import json
        with open(filepath, 'r') as f:
            data = json.load(f)

        self.input_dims = data['input_dims']
        self.d_model = data['d_model']
        self.embeddings = {
            name: np.array(emb) for name, emb in data['embeddings'].items()
        }


class PositionalEncoding:
    """位置编码

    为Transformer提供序列位置信息
    """

    def __init__(self, d_model: int, max_len: int = 5000):
        """初始化位置编码

        Args:
            d_model: 模型维度
            max_len: 最大序列长度
        """
        self.d_model = d_model
        self.max_len = max_len

        # 生成位置编码矩阵
        self.pe = self._generate_positional_encoding()

    def _generate_positional_encoding(self) -> np.ndarray:
        """生成位置编码

        Returns:
            位置编码矩阵 (max_len, d_model)
        """
        pe = np.zeros((self.max_len, self.d_model))

        position = np.arange(0, self.max_len).reshape(-1, 1)
        div_term = np.exp(np.arange(0, self.d_model, 2) * -(np.log(10000.0) / self.d_model))

        pe[:, 0::2] = np.sin(position * div_term)
        pe[:, 1::2] = np.cos(position * div_term)

        return pe

    def encode(self, x: np.ndarray) -> np.ndarray:
        """添加位置编码

        Args:
            x: 输入张量 (seq_len, d_model)

        Returns:
            添加位置编码后的张量
        """
        seq_len = x.shape[0]
        return x + self.pe[:seq_len]
