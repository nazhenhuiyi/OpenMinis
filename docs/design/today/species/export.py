import time, torch, open_clip
torch.backends.mha.set_fastpath_enabled(False)
m, _, pre = open_clip.create_model_and_transforms('hf-hub:imageomics/bioclip-2')
m.eval(); print(pre)
v = m.visual
print('image encoder params %.0fM' % (sum(p.numel() for p in v.parameters()) / 1e6))
class Enc(torch.nn.Module):
    def __init__(s, v): super().__init__(); s.v = v
    def forward(s, x): f = s.v(x); return f / f.norm(dim=-1, keepdim=True)
e = Enc(v).eval(); x = torch.randn(1, 3, 224, 224)
with torch.no_grad():
    for _ in range(2): e(x)
    t = time.perf_counter(); [e(x) for _ in range(5)]; print('torch cpu %.0f ms' % ((time.perf_counter() - t) / 5 * 1000))
    torch.onnx.export(e, x, '/tmp/bc-data/bioclip2_img.onnx', input_names=['image'], output_names=['emb'], opset_version=17, dynamo=False)
torch.save(m.logit_scale.detach(), '/tmp/bc-data/logit_scale.pt'); print('logit_scale', m.logit_scale.exp().item())
