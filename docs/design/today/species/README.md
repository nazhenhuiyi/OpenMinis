# 识别模型和物种名单的生成脚本（SPEC §17）

产物（都不进仓库，体积太大；发布在 GitHub Release [`species-model-v2`](https://github.com/nazhenhuiyi/OpenMinis/releases/tag/species-model-v2)，说明里有 SHA-256。更新时发新的 release）：`bioclip2_img_w8a16.onnx` + `.data`（骁龙 NPU；App 只用 NPU）、`species.bin`、`species.tsv`、`species_prior.bin`（可选）、`species_wiki.{zh-cn,zh-tw,en}.tsv`（图鉴页的维基摘要，可选）。中间数据都在 `/tmp/bc-data/`。

环境：Python 3 + `pip install pybioclip onnx onnxruntime onnxscript`（`export.py`、`quant.py`、`qdq.py`、`fill.py`、`sim.py` 用这个环境；抓数据的脚本只用标准库）。

| 顺序 | 脚本 | 做什么 |
|---|---|---|
| 1 | `export.py` | 导出 BioCLIP 2 图像编码器为 ONNX（fp32，1.2 GB），输出已归一化的 768 维向量 |
| 2 | `quant.py` | 动态 int8 量化（308 MB）。App 已不用 CPU，只在 Mac 上测试 / `sim.py` 时用 |
| 3 | `qdq.py` | 静态量化（w8a16 QDQ，按 QNN 的要求预处理）→ NPU 用的模型；校准图片在 `/tmp/wm/img/` |
| 4 | — | 下载 BioCLIP 的物种向量表：`imageomics/TreeOfLife-200M` 的 `embeddings/txt_emb_species.{npy,json}`（2.7 GB；末尾 15,487 列全是 0，是官方数据的问题） |
| 5 | `cn_species.py` + `cn_captive.py` → `merge_lists.py` | 野外（可验证）和栽培 / 饲养的观察各拉一份，合并成 `cn_all.json`，观察数相加；后面的脚本都读它 |
| 5a | `cn_species.py` | iNat 上中国（place 6903）有可验证观察的全部物种 + 中文名（约 6.3 万种，限速 1 次/秒） |
| 6 | `syn.py` | iNat 学名和 BioCLIP 学名对不上的，用 GBIF 同物异名对齐 |
| 7 | `genus.py` | 属的中文名（iNat） |
| 7a | `genus_wikidata.py` | iNat 没有中文名的属，从 Wikidata 按学名 + 属级查（在 build 之后跑一次，再 build 一次） |
| 7b | `ancestors.py` | 上级分类（界门纲目科）的中文名（iNat），给物种页的分类链用 |
| 8 | `fill.py` | BioCLIP 表里缺的物种，借同属的上级分类拼出完整分类串，用文本编码器补算向量 |
| 9 | `build_species.py` | 观察 ≥3 条的物种 → `species.tsv`（学名、中文名、属、属中文名、类别、iNat id、中国观察数、分类链、最相近的 4 种）+ int8 的 `species.bin` |
| 10 | `sim.py` | 在 Mac 上按 App 的阈值重放一遍，检查误报 |
| 11 | `provinces.py` | 34 个省级行政区的 iNat place id → `provinces.json`（顺序就是 App 里 `PROVINCES` 的顺序） |
| 12 | `geo_months.py` | 每个省有记录的物种、全国每个月的观察数（iNat，限速，可断点续跑；约 1 小时） |
| 13 | `build_prior.py` | → `species_prior.bin`（按省有没有 + 按月的季节分布，和 `species.tsv` 逐行对应）。记录太少（< 2000 种）或被截断在 2 万种上限的省份不判"没有" |
| 14 | `wiki.py` | → `species_wiki.<语言>.tsv`：每个物种和属的维基百科导语第一段（简 / 繁 / 英），每行 `学名 \t 标题 \t 摘要`。先按学名查 Wikidata 的条目，再按中文名 / 学名当标题查；同名的几个条目要核对中文名。中文用 OpenCC 统一成简体或繁体（要 `pip install opencc`），去掉模板留下的空括号。批量 20 篇一次，回应缓存在 `/tmp/bc-data/wiki/`，可断点续跑 |

`PhoneBench.kt`：测速用的独立小 App（instrumentation，锁屏也能跑），比较 CPU / NNAPI / QNN 和内存选项。

**授权**：BioCLIP 2 模型和 TreeOfLife 向量是 MIT；物种名单和中文名来自 iNaturalist API（只用名字和观察数，不用照片）；同物异名来自 GBIF。
