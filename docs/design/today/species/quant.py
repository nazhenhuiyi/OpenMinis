from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic('/tmp/bc-data/bioclip2_img.onnx', '/tmp/bc-data/bioclip2_img_int8.onnx', weight_type=QuantType.QInt8, op_types_to_quantize=['MatMul', 'Gemm'])
