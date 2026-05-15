package com.smartquery.mining;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class AlgorithmRegistry {

    public record ParamDef(
        String key,
        String label,
        String type,
        Number min,
        Number max,
        Number step,
        Object defaultValue,
        String hint,
        List<String> options
    ) {}

    public record AlgorithmDef(
        String id,
        String name,
        String description,
        List<String> modelTypes,
        List<ParamDef> params
    ) {}

    static final List<AlgorithmDef> ALGORITHMS = List.of(
        new AlgorithmDef("random_forest", "随机森林",
            "集成学习方法，通过构建多棵决策树提高预测精度和稳定性",
            List.of("classification", "regression"),
            List.of(
                new ParamDef("n_estimators", "树的数量", "int", 1, 1000, 1, 100, "n_estimators", null),
                new ParamDef("max_depth", "最大深度", "int", 1, 100, 1, 10, "max_depth", null),
                new ParamDef("min_samples_split", "最小分裂样本数", "int", 2, 100, 1, 2, "min_samples_split", null),
                new ParamDef("min_samples_leaf", "叶节点最小样本数", "int", 1, 50, 1, 1, "min_samples_leaf", null)
            )),
        new AlgorithmDef("xgboost", "XGBoost",
            "高效梯度提升算法，适合结构化数据的分类和回归任务",
            List.of("classification", "regression"),
            List.of(
                new ParamDef("n_estimators", "树的数量", "int", 1, 1000, 1, 100, "n_estimators", null),
                new ParamDef("max_depth", "最大深度", "int", 1, 50, 1, 6, "max_depth", null),
                new ParamDef("learning_rate", "学习率", "float", 0.001, 1, 0.01, 0.3, "learning_rate", null),
                new ParamDef("subsample", "子采样率", "float", 0.1, 1, 0.1, 1, "subsample", null)
            )),
        new AlgorithmDef("decision_tree", "决策树",
            "基于特征进行递归分裂的树模型，可解释性强",
            List.of("classification", "regression"),
            List.of(
                new ParamDef("max_depth", "最大深度", "int", 1, 100, 1, 10, "max_depth", null),
                new ParamDef("min_samples_split", "最小分裂样本数", "int", 2, 100, 1, 2, "min_samples_split", null),
                new ParamDef("criterion", "分裂标准", "select", null, null, null, "gini", "criterion", List.of("gini", "entropy"))
            )),
        new AlgorithmDef("logistic_regression", "逻辑回归",
            "线性分类模型，适合二分类和多分类任务",
            List.of("classification"),
            List.of(
                new ParamDef("C", "正则化强度", "float", 0.01, 100, 0.1, 1, "C", null),
                new ParamDef("max_iter", "最大迭代次数", "int", 10, 10000, 1, 100, "max_iter", null),
                new ParamDef("solver", "求解器", "select", null, null, null, "lbfgs", "solver", List.of("lbfgs", "liblinear", "saga"))
            )),
        new AlgorithmDef("svm", "支持向量机",
            "通过寻找最优超平面进行分类，适合中小规模数据集",
            List.of("classification", "regression"),
            List.of(
                new ParamDef("C", "正则化强度", "float", 0.01, 100, 0.1, 1, "C", null),
                new ParamDef("kernel", "核函数", "select", null, null, null, "rbf", "kernel", List.of("rbf", "linear", "poly")),
                new ParamDef("gamma", "Gamma", "select", null, null, null, "scale", "gamma", List.of("scale", "auto"))
            )),
        new AlgorithmDef("knn", "K近邻",
            "基于距离度量的惰性学习算法，简单直观",
            List.of("classification", "regression"),
            List.of(
                new ParamDef("n_neighbors", "邻居数 K", "int", 1, 100, 1, 5, "n_neighbors", null),
                new ParamDef("weights", "权重", "select", null, null, null, "uniform", "weights", List.of("uniform", "distance"))
            )),
        new AlgorithmDef("gradient_boosting", "梯度提升",
            "串行构建弱学习器的集成方法，预测精度高",
            List.of("classification", "regression"),
            List.of(
                new ParamDef("n_estimators", "树的数量", "int", 1, 1000, 1, 100, "n_estimators", null),
                new ParamDef("max_depth", "最大深度", "int", 1, 50, 1, 3, "max_depth", null),
                new ParamDef("learning_rate", "学习率", "float", 0.001, 1, 0.01, 0.1, "learning_rate", null)
            )),
        new AlgorithmDef("lightgbm", "LightGBM",
            "微软开源的快速梯度提升框架，训练速度快、内存占用低",
            List.of("classification", "regression"),
            List.of(
                new ParamDef("n_estimators", "树的数量", "int", 1, 1000, 1, 100, "n_estimators", null),
                new ParamDef("max_depth", "最大深度", "int", 1, 50, 1, -1, "max_depth", null),
                new ParamDef("learning_rate", "学习率", "float", 0.001, 1, 0.01, 0.1, "learning_rate", null),
                new ParamDef("num_leaves", "叶子节点数", "int", 2, 256, 1, 31, "num_leaves", null)
            )),
        new AlgorithmDef("kmeans", "K-Means 聚类",
            "基于距离的无监督聚类算法，将数据分为K个簇",
            List.of("clustering"),
            List.of(
                new ParamDef("n_clusters", "聚类数", "int", 2, 50, 1, 3, "n_clusters", null),
                new ParamDef("max_iter", "最大迭代次数", "int", 10, 1000, 1, 300, "max_iter", null)
            ))
    );

    static final List<ModelTypeDef> MODEL_TYPES = List.of(
        new ModelTypeDef("classification", "分类", "将数据划分到预定义类别"),
        new ModelTypeDef("regression", "回归", "预测连续数值"),
        new ModelTypeDef("clustering", "聚类", "无监督地将数据分组"),
        new ModelTypeDef("anomaly_detection", "异常检测", "识别与正常模式不同的数据点")
    );

    public record ModelTypeDef(String id, String name, String description) {}

    public List<AlgorithmDef> getAll() {
        return ALGORITHMS;
    }

    public List<AlgorithmDef> getByModelType(String modelType) {
        return ALGORITHMS.stream()
            .filter(a -> a.modelTypes().contains(modelType))
            .collect(Collectors.toList());
    }

    public AlgorithmDef getById(String id) {
        return ALGORITHMS.stream()
            .filter(a -> a.id().equals(id))
            .findFirst()
            .orElse(null);
    }

    public List<ModelTypeDef> getModelTypes() {
        return MODEL_TYPES;
    }
}
