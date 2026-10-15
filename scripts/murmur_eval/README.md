# 碎碎念分诊离线评测

用一个端侧小模型（Qwen3-0.6B）判断碎碎念是"记录"、"待办"还是"查询"，并抽出时间和事情。

```sh
brew install llama.cpp
curl -L -o ~/models/qwen3/Qwen3-0.6B-Q8_0.gguf \
  https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf
# 只用 CPU、4 线程，接近手机
llama-server -m ~/models/qwen3/Qwen3-0.6B-Q8_0.gguf --port 8089 -c 2048 -np 1 -ngl 0 -t 4

python3 scripts/murmur_eval/eval.py --prompt catchat2        # 直接取最可能的输出
python3 scripts/murmur_eval/threshold.py --prompt catchat2   # 按 1-P(记录) 扫阈值
```

`dataset.tsv`：文本、是/否、类型、时间、事情、是否有争议（1）。目前是模拟数据，要换成真实碎碎念再下结论。
