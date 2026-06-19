# smart-query Python 执行镜像（数据挖掘/分析用）
# 后端通过 docker socket 以 `docker run smart-query-python:latest` 调用（见 PythonExecutor.buildDockerProcess）
FROM python:3.11-slim

# 挖掘算法依赖：覆盖 AlgorithmRegistry 的 9 种算法（sklearn 系 + xgboost + lightgbm）
# + 数据处理（pandas/numpy）+ 绘图（matplotlib）+ DB 访问（pymysql/sqlalchemy）+ 模型序列化（joblib）
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

WORKDIR /workspace
