# 识别模型和物种名单的生成脚本（SPEC §17）

产物（都不进仓库，体积太大）：`bioclip2_img_int8.onnx`（CPU）、`bioclip2_img_w8a16.onnx` + `.data`（骁龙 NPU）、`species.bin`、`species.tsv`。中间数据都在 `/tmp/bc-data/`。

环境：Python 3 + `pip install pybioclip onnx onnxruntime onnxscript`（`export.py`、`quant.py`、`qdq.py`、`fill.py`、`sim.py` 用这个环境；抓数据的脚本只用标准库）。

| 顺序 | 脚本 | 做什么 |
|---|---|---|
| 1 | `export.py` | 导出 BioCLIP 2 图像编码器为 ONNX（fp32，1.2 GB），输出已归一化的 768 维向量 |
| 2 | `quant.py` | 动态 int8 量化 → CPU 用的 308 MB 模型 |
| 3 | `qdq.py` | 静态量化（w8a16 QDQ，按 QNN 的要求预处理）→ NPU 用的模型；校准图片在 `/tmp/wm/img/` |
| 4 | — | 下载 BioCLIP 的物种向量表：`imageomics/TreeOfLife-200M` 的 `embeddings/txt_emb_species.{npy,json}`（2.7 GB；末尾 15,487 列全是 0，是官方数据的问题） |
| 5 | `cn_species.py` | iNat 上中国（place 6903）有可验证观察的全部物种 + 中文名（约 6.3 万种，限速 1 次/秒） |
| 6 | `syn.py` | iNat 学名和 BioCLIP 学名对不上的，用 GBIF 同物异名对齐 |
| 7 | `genus.py` | 属的中文名（iNat） |
| 8 | `fill.py` | BioCLIP 表里缺的物种，借同属的上级分类拼出完整分类串，用文本编码器补算向量 |
| 9 | `build_species.py` | 观察 ≥3 条的物种 → `species.tsv` + int8 的 `species.bin` |
| 10 | `sim.py` | 在 Mac 上按 App 的阈值重放一遍，检查误报 |

`PhoneBench.kt`：测速用的独立小 App（instrumentation，锁屏也能跑），比较 CPU / NNAPI / QNN 和内存选项。

**授权**：BioCLIP 2 模型和 TreeOfLife 向量是 MIT；物种名单和中文名来自 iNaturalist API（只用名字和观察数，不用照片）；同物异名来自 GBIF。
