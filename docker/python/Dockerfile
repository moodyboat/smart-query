# smart-query Python 执行镜像（数据挖掘/分析用）
# 后端通过 docker socket 以 `docker run smart-query-python:latest` 调用（见 PythonExecutor.buildDockerProcess）
FROM python:3.11-slim

# 挖掘算法依赖：覆盖 AlgorithmRegistry 的 9 种算法（sklearn 系 + xgboost + lightgbm）
# + 数据处理（pandas/numpy）+ 绘图（matplotlib）+ DB 访问（pymysql/sqlalchemy）+ 模型序列化（joblib）
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

# DM8 驱动单独成层，使业务库驱动升级不触发 200MB+ 算法依赖重装。
COPY requirements-dm8.txt /tmp/requirements-dm8.txt
RUN --mount=type=cache,id=smart-query-dm8-pip,target=/root/.cache/pip \
    pip install --default-timeout=300 --retries=10 -r /tmp/requirements-dm8.txt

# dmPython wheel bundles DMSSL/OpenSSL shared libraries outside the default linker path.
ENV LD_LIBRARY_PATH=/usr/local/lib/python3.11/site-packages/dmpython.libs:/usr/local/lib/python3.11/site-packages/dmssl

# LightGBM（以及部分 OpenMP 算法实现）的 Linux 运行时依赖。
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgomp1 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
