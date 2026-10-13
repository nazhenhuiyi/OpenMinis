import sys; import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glob, numpy as np, onnx
from prep import prep
from onnxruntime.quantization import CalibrationDataReader, quantize, QuantType
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model
src = '/tmp/bc-data/bioclip2_img.onnx'; pre = '/tmp/bc-data/bioclip2_img_pre.onnx'; out = '/tmp/bc-data/bioclip2_img_w8a16.onnx'
changed = qnn_preprocess_model(src, pre, save_as_external_data=True)
if not changed: pre = src
class R(CalibrationDataReader):
    def __init__(s): s.it = iter([{'image': prep(p)} for p in sorted(glob.glob('/tmp/wm/img/*.jpg'))])
    def get_next(s): return next(s.it, None)
cfg = get_qnn_qdq_config(pre, R(), activation_type=QuantType.QUInt16, weight_type=QuantType.QUInt8)
quantize(pre, out, cfg)
print('done')
