# backend/app/algorithms/transformer_lstm/model.py
# Transformer-LSTM混合网络模型

import numpy as np
from typing import Dict, List, Optional, Tuple
from app.algorithms.transformer_lstm.attention import MultiHeadAttention
from app.algorithms.transformer_lstm.embedding import FeatureEmbedding, PositionalEncoding


class TransformerLSTMModel:
    """Transformer-LSTM混合网络

    架构:
    - Transformer编码器: 捕捉长时间依赖关系，自注意力机制可自动发现关键特征
    - LSTM解码器: 处理时序预测任务，保持序列连续性
    - 多头预测: 同时预测未来多个时间步 (30/60/90分钟)
    """

    def __init__(self, config: dict = None):
        """初始化Transformer-LSTM模型

        Args:
            config: 模型配置
        """
        # 默认配置
        default_config = {
            'd_model': 64,
            'num_heads': 4,
            'num_encoder_layers': 2,
            'lstm_hidden_size': 64,
            'lstm_num_layers': 2,
            'input_dims': {
                'glucose': 22,
                'meal': 8,
                'exercise': 7,
                'temporal': 4
            },
            'output_dims': {
                'horizon_30': 3,  # [value, upper_offset, lower_offset]
                'horizon_60': 3,
                'horizon_90': 3
            },
            'dropout': 0.1
        }

        self.config = config or default_config
        self.d_model = self.config['d_model']
        self.num_heads = self.config['num_heads']

        # 初始化组件
        self.feature_embedding = FeatureEmbedding(
            self.config['input_dims'],
            self.d_model
        )
        self.positional_encoding = PositionalEncoding(self.d_model)

        # Transformer编码器层
        self.attention_layers = []
        for _ in range(self.config['num_encoder_layers']):
            self.attention_layers.append(
                MultiHeadAttention(self.d_model, self.num_heads)
            )

        # LSTM层权重
        self.lstm_hidden_size = self.config['lstm_hidden_size']
        self.lstm_num_layers = self.config['lstm_num_layers']

        # LSTM权重初始化
        self.lstm_weights = self._init_lstm_weights()

        # 输出层权重
        self.output_weights = self._init_output_weights()

        # 训练状态
        self.is_trained = False

    def _init_lstm_weights(self) -> dict:
        """初始化LSTM权重

        Returns:
            LSTM权重字典
        """
        weights = {}
        for layer in range(self.lstm_num_layers):
            input_size = self.d_model if layer == 0 else self.lstm_hidden_size
            scale = np.sqrt(2.0 / (input_size + self.lstm_hidden_size))

            # 输入门
            weights[f'W_i_{layer}'] = np.random.randn(input_size, self.lstm_hidden_size) * scale
            weights[f'U_i_{layer}'] = np.random.randn(self.lstm_hidden_size, self.lstm_hidden_size) * scale
            weights[f'b_i_{layer}'] = np.zeros(self.lstm_hidden_size)

            # 遗忘门
            weights[f'W_f_{layer}'] = np.random.randn(input_size, self.lstm_hidden_size) * scale
            weights[f'U_f_{layer}'] = np.random.randn(self.lstm_hidden_size, self.lstm_hidden_size) * scale
            weights[f'b_f_{layer}'] = np.ones(self.lstm_hidden_size)  # 初始化为1

            # 细胞门
            weights[f'W_c_{layer}'] = np.random.randn(input_size, self.lstm_hidden_size) * scale
            weights[f'U_c_{layer}'] = np.random.randn(self.lstm_hidden_size, self.lstm_hidden_size) * scale
            weights[f'b_c_{layer}'] = np.zeros(self.lstm_hidden_size)

            # 输出门
            weights[f'W_o_{layer}'] = np.random.randn(input_size, self.lstm_hidden_size) * scale
            weights[f'U_o_{layer}'] = np.random.randn(self.lstm_hidden_size, self.lstm_hidden_size) * scale
            weights[f'b_o_{layer}'] = np.zeros(self.lstm_hidden_size)

        return weights

    def _init_output_weights(self) -> dict:
        """初始化输出层权重

        Returns:
            输出层权重字典
        """
        weights = {}
        scale = np.sqrt(2.0 / self.lstm_hidden_size)

        for horizon, dim in self.config['output_dims'].items():
            weights[f'W_{horizon}'] = np.random.randn(self.lstm_hidden_size, dim) * scale
            weights[f'b_{horizon}'] = np.zeros(dim)

        # 风险分类层
        weights['W_risk'] = np.random.randn(self.lstm_hidden_size, 3) * scale
        weights['b_risk'] = np.zeros(3)

        return weights

    def _sigmoid(self, x: np.ndarray) -> np.ndarray:
        """Sigmoid激活函数"""
        return 1 / (1 + np.exp(-np.clip(x, -500, 500)))

    def _tanh(self, x: np.ndarray) -> np.ndarray:
        """Tanh激活函数"""
        return np.tanh(np.clip(x, -500, 500))

    def _relu(self, x: np.ndarray) -> np.ndarray:
        """ReLU激活函数"""
        return np.maximum(0, x)

    def _softmax(self, x: np.ndarray) -> np.ndarray:
        """Softmax激活函数"""
        exp_x = np.exp(x - np.max(x, axis=-1, keepdims=True))
        return exp_x / np.sum(exp_x, axis=-1, keepdims=True)

    def _softplus(self, x: np.ndarray) -> np.ndarray:
        """Softplus激活函数 (确保非负)"""
        return np.log(1 + np.exp(x))

    def _lstm_cell(self, x: np.ndarray, h_prev: np.ndarray, c_prev: np.ndarray,
                    layer: int) -> Tuple[np.ndarray, np.ndarray]:
        """LSTM单元

        Args:
            x: 输入
            h_prev: 上一时刻隐藏状态
            c_prev: 上一时刻细胞状态
            layer: 层索引

        Returns:
            (隐藏状态, 细胞状态)
        """
        # 输入门
        i = self._sigmoid(
            np.dot(x, self.lstm_weights[f'W_i_{layer}']) +
            np.dot(h_prev, self.lstm_weights[f'U_i_{layer}']) +
            self.lstm_weights[f'b_i_{layer}']
        )

        # 遗忘门
        f = self._sigmoid(
            np.dot(x, self.lstm_weights[f'W_f_{layer}']) +
            np.dot(h_prev, self.lstm_weights[f'U_f_{layer}']) +
            self.lstm_weights[f'b_f_{layer}']
        )

        # 细胞门
        c_tilde = self._tanh(
            np.dot(x, self.lstm_weights[f'W_c_{layer}']) +
            np.dot(h_prev, self.lstm_weights[f'U_c_{layer}']) +
            self.lstm_weights[f'b_c_{layer}']
        )

        # 输出门
        o = self._sigmoid(
            np.dot(x, self.lstm_weights[f'W_o_{layer}']) +
            np.dot(h_prev, self.lstm_weights[f'U_o_{layer}']) +
            self.lstm_weights[f'b_o_{layer}']
        )

        # 更新细胞状态
        c = f * c_prev + i * c_tilde

        # 更新隐藏状态
        h = o * self._tanh(c)

        return h, c

    def _transformer_encoder(self, x: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        """Transformer编码器

        Args:
            x: 输入序列 (seq_len, d_model)

        Returns:
            (编码输出, 注意力权重)
        """
        # 添加位置编码
        x = self.positional_encoding.encode(x)

        # 多层Transformer编码器
        attention_weights = None
        for attention_layer in self.attention_layers:
            # 多头注意力
            attn_output, attention_weights = attention_layer.forward(x)

            # 残差连接和层归一化
            x = x + attn_output
            x = self._layer_norm(x)

            # 前馈网络
            ff_output = self._feed_forward(x)

            # 残差连接和层归一化
            x = x + ff_output
            x = self._layer_norm(x)

        return x, attention_weights

    def _layer_norm(self, x: np.ndarray, epsilon: float = 1e-6) -> np.ndarray:
        """层归一化

        Args:
            x: 输入
            epsilon: 小常数

        Returns:
            归一化后的输出
        """
        mean = np.mean(x, axis=-1, keepdims=True)
        std = np.std(x, axis=-1, keepdims=True)
        return (x - mean) / (std + epsilon)

    def _feed_forward(self, x: np.ndarray) -> np.ndarray:
        """前馈网络

        Args:
            x: 输入

        Returns:
            输出
        """
        # 简单的两层前馈网络
        d_ff = self.d_model * 4
        W1 = np.random.randn(self.d_model, d_ff) * np.sqrt(2.0 / self.d_model)
        b1 = np.zeros(d_ff)
        W2 = np.random.randn(d_ff, self.d_model) * np.sqrt(2.0 / d_ff)
        b2 = np.zeros(self.d_model)

        hidden = self._relu(np.dot(x, W1) + b1)
        output = np.dot(hidden, W2) + b2

        return output

    def forward(self, feature_sequence: List[Dict]) -> Dict:
        """前向传播

        Args:
            feature_sequence: 特征字典序列

        Returns:
            预测结果字典
        """
        # 特征嵌入
        embedded = self.feature_embedding.embed_sequence(feature_sequence)

        # Transformer编码器
        encoder_output, attention_weights = self._transformer_encoder(embedded)

        # LSTM解码器
        batch_size = 1
        h = [np.zeros(self.lstm_hidden_size) for _ in range(self.lstm_num_layers)]
        c = [np.zeros(self.lstm_hidden_size) for _ in range(self.lstm_num_layers)]

        for t in range(len(encoder_output)):
            x = encoder_output[t]
            for layer in range(self.lstm_num_layers):
                h[layer], c[layer] = self._lstm_cell(x, h[layer], c[layer], layer)
                x = h[layer]

        # 使用最后一个隐藏状态进行预测
        final_hidden = h[-1]

        # 多头预测
        predictions = {}
        for horizon, dim in self.config['output_dims'].items():
            output = np.dot(final_hidden, self.output_weights[f'W_{horizon}']) + self.output_weights[f'b_{horizon}']

            # 解析输出
            value = output[0]
            upper_offset = self._softplus(output[1])
            lower_offset = self._softplus(output[2])

            predictions[horizon] = {
                'value': float(value),
                'upper': float(value + upper_offset),
                'lower': float(value - lower_offset)
            }

        # 风险分类
        risk_logits = np.dot(final_hidden, self.output_weights['W_risk']) + self.output_weights['b_risk']
        risk_probs = self._softmax(risk_logits)

        risk_level = ['normal', 'low_risk', 'high_risk'][np.argmax(risk_probs)]

        return {
            'predictions': predictions,
            'risk_level': risk_level,
            'risk_logits': risk_logits.tolist(),
            'risk_probs': risk_probs.tolist(),
            'attention_weights': attention_weights.tolist() if attention_weights is not None else None
        }

    def predict(self, features: Dict, horizons: List[int] = [30, 60, 90]) -> Dict:
        """预测血糖值

        Args:
            features: 当前特征字典
            horizons: 预测时域列表

        Returns:
            预测结果
        """
        # 创建特征序列 (单时间步)
        feature_sequence = [features]

        # 前向传播
        result = self.forward(feature_sequence)

        # 过滤需要的时域
        filtered_predictions = {}
        for horizon in horizons:
            key = f'horizon_{horizon}'
            if key in result['predictions']:
                filtered_predictions[key] = result['predictions'][key]

        result['predictions'] = filtered_predictions
        return result

    def get_top_attention_factors(self, feature_sequence: List[Dict],
                                    feature_names: List[str],
                                    top_k: int = 5) -> List[Dict]:
        """获取注意力权重最高的Top-K因素

        Args:
            feature_sequence: 特征序列
            feature_names: 特征名称列表
            top_k: 返回前K个因素

        Returns:
            Top-K因素列表
        """
        # 嵌入特征
        embedded = self.feature_embedding.embed_sequence(feature_sequence)

        # 获取注意力权重
        _, attention_weights = self._transformer_encoder(embedded)

        if attention_weights is None:
            return []

        # 平均注意力权重
        avg_weights = np.mean(attention_weights, axis=0)

        # 获取最后一个时间步对其他时间步的注意力
        last_step_weights = avg_weights[-1]

        # 获取Top-K
        top_indices = np.argsort(last_step_weights)[-top_k:][::-1]

        factors = []
        for idx in top_indices:
            if idx < len(feature_names):
                factors.append({
                    'time_index': int(idx),
                    'weight': float(last_step_weights[idx]),
                    'feature': feature_names[idx]
                })

        return factors

    def save(self, filepath: str):
        """保存模型

        Args:
            filepath: 保存路径
        """
        import json
        data = {
            'config': self.config,
            'feature_embedding': {
                'input_dims': self.feature_embedding.input_dims,
                'd_model': self.feature_embedding.d_model,
                'embeddings': {
                    name: emb.tolist() for name, emb in self.feature_embedding.embeddings.items()
                }
            },
            'lstm_weights': {k: v.tolist() for k, v in self.lstm_weights.items()},
            'output_weights': {k: v.tolist() for k, v in self.output_weights.items()},
            'is_trained': self.is_trained
        }
        with open(filepath, 'w') as f:
            json.dump(data, f)

    def load(self, filepath: str):
        """加载模型

        Args:
            filepath: 加载路径
        """
        import json
        with open(filepath, 'r') as f:
            data = json.load(f)

        self.config = data['config']
        self.d_model = self.config['d_model']
        self.num_heads = self.config['num_heads']

        # 恢复特征嵌入
        self.feature_embedding = FeatureEmbedding(
            data['feature_embedding']['input_dims'],
            data['feature_embedding']['d_model']
        )
        self.feature_embedding.embeddings = {
            name: np.array(emb) for name, emb in data['feature_embedding']['embeddings'].items()
        }

        # 恢复LSTM权重
        self.lstm_weights = {k: np.array(v) for k, v in data['lstm_weights'].items()}

        # 恢复输出权重
        self.output_weights = {k: np.array(v) for k, v in data['output_weights'].items()}

        self.is_trained = data.get('is_trained', False)
