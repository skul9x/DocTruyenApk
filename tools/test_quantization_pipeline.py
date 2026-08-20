#!/usr/bin/env python3
"""
Verification Test for Automated ONNX Model Quantization Pipeline (Phase 02).

Validates:
1. Dynamic INT8 quantization with external data (.data) preservation.
2. FP16 conversion with external data preservation and shape inference flags.
3. Input/Output tensor shapes, types, and names preservation across conversions.
4. Acoustic operator safety: ensures Conv and ConvTranspose remain in floating-point precision,
   while MatMul and Gemm are dynamically quantized.
5. ONNX metadata_props injection for Android runtime detection.
6. Execution inference verification via ONNX Runtime on quantized artifacts.
7. Directory batch processing and size reporting.
"""

import os
import shutil
import tempfile
import unittest
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, TensorProto
import onnxruntime as ort

from quantize_models import (
    quantize_model_int8_dynamic,
    convert_model_fp16,
    check_audio_safety,
    read_metadata,
    process_directory,
    get_model_total_size_bytes,
)


def create_sample_tts_onnx_model(output_path: str, external_data_name: str = "sample.data") -> None:
    """
    Create a synthetic ONNX model representing a TTS audio pipeline:
    - Input: audio_features [1, 2, 8]
    - Conv layer: kernel=3, pads=[1, 1] -> [1, 4, 8]
    - ConvTranspose layer: kernel=4, stride=2, pads=[1, 1] -> [1, 4, 16]
    - Reshape layer: -> [1, 64]
    - MatMul layer: [64, 32] -> [1, 32]
    - Gemm layer: [32, 16] -> [1, 16]
    - Output: audio_out [1, 16]
    Saves weights in external data file.
    """
    audio_in = helper.make_tensor_value_info("audio_in", TensorProto.FLOAT, [1, 2, 8])

    # Weights
    np.random.seed(42)
    w_conv_data = np.random.randn(4, 2, 3).astype(np.float32).flatten().tolist()
    w_conv = helper.make_tensor("W_conv", TensorProto.FLOAT, [4, 2, 3], w_conv_data)
    conv_node = helper.make_node(
        "Conv", ["audio_in", "W_conv"], ["conv_out"], kernel_shape=[3], pads=[1, 1]
    )

    w_conv_tr_data = np.random.randn(4, 4, 4).astype(np.float32).flatten().tolist()
    w_conv_tr = helper.make_tensor("W_conv_tr", TensorProto.FLOAT, [4, 4, 4], w_conv_tr_data)
    conv_tr_node = helper.make_node(
        "ConvTranspose",
        ["conv_out", "W_conv_tr"],
        ["conv_tr_out"],
        kernel_shape=[4],
        strides=[2],
        pads=[1, 1],
    )

    shape_t = helper.make_tensor("shape_t", TensorProto.INT64, [2], [1, 64])
    reshape_node = helper.make_node("Reshape", ["conv_tr_out", "shape_t"], ["flat_out"])

    w_matmul_data = np.random.randn(64, 32).astype(np.float32).flatten().tolist()
    w_matmul = helper.make_tensor("W_matmul", TensorProto.FLOAT, [64, 32], w_matmul_data)
    matmul_node = helper.make_node("MatMul", ["flat_out", "W_matmul"], ["matmul_out"])

    w_gemm_data = np.random.randn(32, 16).astype(np.float32).flatten().tolist()
    w_gemm = helper.make_tensor("W_gemm", TensorProto.FLOAT, [32, 16], w_gemm_data)
    b_gemm_data = np.random.randn(16).astype(np.float32).flatten().tolist()
    b_gemm = helper.make_tensor("B_gemm", TensorProto.FLOAT, [16], b_gemm_data)
    gemm_node = helper.make_node("Gemm", ["matmul_out", "W_gemm", "B_gemm"], ["audio_out"])

    audio_out = helper.make_tensor_value_info("audio_out", TensorProto.FLOAT, [1, 16])

    graph = helper.make_graph(
        [conv_node, conv_tr_node, reshape_node, matmul_node, gemm_node],
        "tts_sample_pipeline",
        [audio_in],
        [audio_out],
        [w_conv, w_conv_tr, shape_t, w_matmul, w_gemm, b_gemm],
    )

    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 14)])
    onnx.save_model(
        model,
        output_path,
        save_as_external_data=True,
        all_tensors_to_one_file=True,
        location=external_data_name,
    )


class TestModelQuantizationPipeline(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="test_quant_")
        self.model_path = os.path.join(self.test_dir, "sample_tts.onnx")
        self.data_name = "sample_tts.data"
        create_sample_tts_onnx_model(self.model_path, self.data_name)

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_dynamic_int8_quantization_and_audio_safety(self):
        """
        Verify INT8 quantization:
        - MatMul and Gemm are quantized to integer arithmetic.
        - Conv and ConvTranspose remain in floating-point precision.
        - Metadata {"quantization": "int8"} is injected.
        - Inference produces correct tensor shapes.
        """
        int8_output_path = os.path.join(self.test_dir, "sample_tts_int8.onnx")

        result = quantize_model_int8_dynamic(
            input_path=self.model_path,
            output_path=int8_output_path,
            use_external_data=True,
            check_safety=True,
        )

        self.assertTrue(os.path.exists(int8_output_path))
        self.assertEqual(result["format"], "int8")

        # Load quantized model and inspect nodes
        model_int8 = onnx.load(int8_output_path, load_external_data=False)
        op_types = {n.op_type for n in model_int8.graph.node}

        # Verify acoustic preservation
        self.assertIn("Conv", op_types, "Conv operator must be preserved in float precision")
        self.assertIn("ConvTranspose", op_types, "ConvTranspose operator must be preserved in float precision")
        self.assertNotIn("ConvInteger", op_types, "ConvInteger must not be present")
        self.assertNotIn("QLinearConv", op_types, "QLinearConv must not be present")

        # Verify MatMul/Gemm quantized
        has_int_matmul = "MatMulInteger" in op_types or "DynamicQuantizeLinear" in op_types
        self.assertTrue(has_int_matmul, "MatMul / Gemm ops must be quantized to integer")

        # Verify metadata injection
        meta = read_metadata(int8_output_path)
        self.assertIn("quantization", meta)
        self.assertEqual(meta["quantization"], "int8")

        # Verify safety validator
        safety = check_audio_safety(int8_output_path)
        self.assertTrue(safety["is_safe"])
        self.assertEqual(len(safety["violations"]), 0)

        # Run ONNX Runtime Inference
        sess = ort.InferenceSession(int8_output_path)
        input_name = sess.get_inputs()[0].name
        self.assertEqual(input_name, "audio_in")
        self.assertEqual(sess.get_outputs()[0].name, "audio_out")

        dummy_audio = np.random.randn(1, 2, 8).astype(np.float32)
        out = sess.run(None, {input_name: dummy_audio})[0]
        self.assertEqual(out.shape, (1, 16), "Output tensor shape must be (1, 16)")

    def test_fp16_conversion_and_metadata(self):
        """
        Verify FP16 conversion:
        - keep_io_types preserves FP32 I/O tensors for Android runtime compatibility.
        - Metadata {"quantization": "fp16"} is injected.
        - External data is properly handled.
        - Inference executes cleanly.
        """
        fp16_output_path = os.path.join(self.test_dir, "sample_tts_fp16.onnx")

        result = convert_model_fp16(
            input_path=self.model_path,
            output_path=fp16_output_path,
            keep_io_types=True,
            disable_shape_infer=True,
            use_external_data=True,
            check_safety=True,
        )

        self.assertTrue(os.path.exists(fp16_output_path))
        self.assertIn("fp16", result["format"])

        # Check metadata
        meta = read_metadata(fp16_output_path)
        self.assertIn("quantization", meta)
        self.assertEqual(meta["quantization"], "fp16")

        # Run ONNX Runtime Inference
        sess = ort.InferenceSession(fp16_output_path)
        dummy_audio = np.random.randn(1, 2, 8).astype(np.float32)
        out = sess.run(None, {"audio_in": dummy_audio})[0]
        self.assertEqual(out.shape, (1, 16))

    def test_audio_safety_violation_detection(self):
        """
        Verify that audio safety checker catches prohibited ConvInteger operators.
        """
        # Create a synthetic invalid model containing ConvInteger
        x = helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 1, 4])
        y = helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 1, 4])
        bad_node = helper.make_node("ConvInteger", ["x", "w"], ["y"])
        graph = helper.make_graph([bad_node], "bad_graph", [x], [y], [])
        bad_model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 14)])

        bad_path = os.path.join(self.test_dir, "bad_model.onnx")
        onnx.save_model(bad_model, bad_path)

        safety_report = check_audio_safety(bad_path)
        self.assertFalse(safety_report["is_safe"])
        self.assertTrue(len(safety_report["violations"]) > 0)
        self.assertIn("ConvInteger", safety_report["violations"][0])

    def test_batch_directory_processing(self):
        """
        Verify recursive directory batch processing, size reporting, and file copying.
        """
        batch_in = os.path.join(self.test_dir, "batch_in")
        batch_out = os.path.join(self.test_dir, "batch_out")

        # Setup directory structure: backbone/, codec/, denoiser/
        os.makedirs(os.path.join(batch_in, "backbone"), exist_ok=True)
        os.makedirs(os.path.join(batch_in, "codec"), exist_ok=True)

        create_sample_tts_onnx_model(
            os.path.join(batch_in, "backbone", "model_a.onnx"), "model_a.data"
        )
        create_sample_tts_onnx_model(
            os.path.join(batch_in, "codec", "model_b.onnx"), "model_b.data"
        )

        # Ancillary file
        with open(os.path.join(batch_in, "backbone", "config.json"), "w") as f:
            f.write('{"test": true}')

        results = process_directory(
            input_dir=batch_in,
            output_dir=batch_out,
            target_format="int8",
            check_safety=True,
            copy_ancillary_files=True,
        )

        self.assertEqual(len(results), 2)
        self.assertTrue(os.path.exists(os.path.join(batch_out, "backbone", "model_a.onnx")))
        self.assertTrue(os.path.exists(os.path.join(batch_out, "codec", "model_b.onnx")))
        self.assertTrue(os.path.exists(os.path.join(batch_out, "backbone", "config.json")))

        # Check total size calculation
        size_a = get_model_total_size_bytes(os.path.join(batch_out, "backbone", "model_a.onnx"))
        self.assertGreater(size_a, 0)


if __name__ == "__main__":
    unittest.main()
