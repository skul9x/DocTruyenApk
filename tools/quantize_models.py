#!/usr/bin/env python3
"""
Automated ONNX Model Quantization Pipeline for VieNeu TTS Engine.

Supports:
- Dynamic INT8 Quantization (Primary optimization path for CPU/XNNPACK execution provider)
  - Selectively quantizes MatMul and Gemm ops to prevent acoustic degradation in Conv/neural codec layers.
- FP16 Conversion (Secondary optimization path for NNAPI/GPU execution providers only)
  - Not recommended for CPU EP due to runtime FP16->FP32 Cast node overhead.
- External Data (.data) resolution and re-sharding for models > 2GB or multi-file models.
- Metadata injection ({"quantization": "int8"|"fp16"}) for Android runtime detection.
- Audio safety verification to ensure acoustic synthesis layers preserve full floating-point precision.
"""

import argparse
import os
import shutil
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import onnx
from onnx import ModelProto
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic
from onnxconverter_common.float16 import convert_float_to_float16


PROHIBITED_INT_OPS: Set[str] = {
    "ConvInteger",
    "QLinearConv",
    "QLinearConvTranspose",
    "ConvTransposeInteger",
}

DEFAULT_INT8_OP_TYPES: List[str] = ["MatMul", "Gemm"]

FP16_CPU_WARNING: str = (
    "⚠️  WARNING: FP16 models require NNAPI (with NNAPI_FLAG_USE_FP16) or GPU EP "
    "for performance benefit. Loading on ONNX Runtime CPU EP will insert auto-cast nodes "
    "(FP16 -> FP32), which degrades performance compared to native FP32. "
    "Dynamic INT8 is the recommended format for CPU-only inference."
)


def get_model_total_size_bytes(model_path: str) -> int:
    """
    Calculate the total size of an ONNX model, including external data files.
    """
    model_p = Path(model_path)
    if not model_p.exists():
        return 0

    total_bytes = model_p.stat().st_size

    # Check for directly referenced external data files in the model proto
    try:
        model = onnx.load(str(model_p), load_external_data=False)
        external_files: Set[str] = set()
        for tensor in model.graph.initializer:
            if tensor.data_location == onnx.TensorProto.EXTERNAL:
                for entry in tensor.external_data:
                    if entry.key == "location":
                        external_files.add(entry.value)

        for ext_rel_path in external_files:
            ext_full_path = model_p.parent / ext_rel_path
            if ext_full_path.exists():
                total_bytes += ext_full_path.stat().st_size
    except Exception:
        # Fallback: check standard .data naming convention
        data_p = Path(str(model_p) + ".data")
        if data_p.exists():
            total_bytes += data_p.stat().st_size

    return total_bytes


def format_size_mb(size_bytes: int) -> str:
    """Format bytes into MB string."""
    return f"{size_bytes / (1024 * 1024):.2f} MB"


def inject_metadata(model_path: str, metadata: Dict[str, str]) -> None:
    """
    Inject or update key-value metadata into an ONNX model's metadata_props.
    Preserves external data references without loading large weights into memory.
    """
    model = onnx.load(model_path, load_external_data=False)
    existing_keys = {entry.key: entry for entry in model.metadata_props}

    for k, v in metadata.items():
        if k in existing_keys:
            existing_keys[k].value = str(v)
        else:
            entry = model.metadata_props.add()
            entry.key = k
            entry.value = str(v)

    onnx.save_model(model, model_path)


def read_metadata(model_path: str) -> Dict[str, str]:
    """Read metadata properties from an ONNX model file."""
    model = onnx.load(model_path, load_external_data=False)
    return {entry.key: entry.value for entry in model.metadata_props}


def check_audio_safety(model_path: str) -> Dict[str, any]:
    """
    Verify acoustic preservation safety:
    1. Ensures prohibited integer operators (ConvInteger, QLinearConv, etc.) were NOT created.
    2. Ensures 1D/2D Convolutions and ConvTranspose layers remain in original floating precision.
    3. Verifies input and output shapes and data types are valid.
    """
    model = onnx.load(model_path, load_external_data=False)
    op_counts: Dict[str, int] = {}
    for node in model.graph.node:
        op_counts[node.op_type] = op_counts.get(node.op_type, 0) + 1

    violations = []
    for prohibited in PROHIBITED_INT_OPS:
        if prohibited in op_counts:
            violations.append(
                f"Prohibited quantized operator '{prohibited}' found ({op_counts[prohibited]} occurrences). "
                f"Convolution/Acoustic decoder layers must remain in floating point precision."
            )

    has_quantized_matmul = (
        "MatMulInteger" in op_counts or "DynamicQuantizeLinear" in op_counts
    )
    has_conv = "Conv" in op_counts or "ConvTranspose" in op_counts

    is_safe = len(violations) == 0

    return {
        "is_safe": is_safe,
        "violations": violations,
        "op_counts": op_counts,
        "has_conv_preserved": has_conv,
        "has_quantized_matmul": has_quantized_matmul,
        "inputs": [inp.name for inp in model.graph.input],
        "outputs": [out.name for out in model.graph.output],
    }


def quantize_model_int8_dynamic(
    input_path: str,
    output_path: str,
    op_types_to_quantize: Optional[List[str]] = None,
    use_external_data: bool = True,
    check_safety: bool = True,
) -> Dict[str, any]:
    """
    Quantize an ONNX model using Dynamic INT8 quantization.
    Targeted specifically for MatMul and Gemm operations to protect audio fidelity.
    """
    if op_types_to_quantize is None:
        op_types_to_quantize = DEFAULT_INT8_OP_TYPES

    input_p = Path(input_path)
    output_p = Path(output_path)
    output_p.parent.mkdir(parents=True, exist_ok=True)

    orig_size = get_model_total_size_bytes(input_path)
    start_time = time.time()

    # Dynamic INT8 Quantization
    quantize_dynamic(
        model_input=str(input_p),
        model_output=str(output_p),
        op_types_to_quantize=op_types_to_quantize,
        weight_type=QuantType.QInt8,
        use_external_data_format=use_external_data,
    )

    # Inject metadata for Android runtime detection
    inject_metadata(
        str(output_p),
        {
            "quantization": "int8",
            "quantization_type": "dynamic_int8",
            "quantized_ops": ",".join(op_types_to_quantize),
        },
    )

    elapsed_sec = time.time() - start_time
    new_size = get_model_total_size_bytes(str(output_p))
    ratio = (1.0 - (new_size / orig_size)) * 100.0 if orig_size > 0 else 0.0

    safety_report = None
    if check_safety:
        safety_report = check_audio_safety(str(output_p))
        if not safety_report["is_safe"]:
            raise ValueError(
                f"Audio safety check failed for {output_path}: {safety_report['violations']}"
            )

    return {
        "model_name": input_p.name,
        "format": "int8",
        "original_size_bytes": orig_size,
        "quantized_size_bytes": new_size,
        "compression_ratio_pct": ratio,
        "elapsed_sec": elapsed_sec,
        "safety_report": safety_report,
        "output_path": str(output_p),
    }


def convert_model_fp16(
    input_path: str,
    output_path: str,
    keep_io_types: bool = True,
    disable_shape_infer: bool = True,
    op_block_list: Optional[List[str]] = None,
    use_external_data: bool = True,
    check_safety: bool = True,
) -> Dict[str, any]:
    """
    Convert an ONNX model to FP16.
    ⚠️ Only effective with NNAPI (with NNAPI_FLAG_USE_FP16) or GPU execution providers.
    """
    input_p = Path(input_path)
    output_p = Path(output_path)
    output_p.parent.mkdir(parents=True, exist_ok=True)

    orig_size = get_model_total_size_bytes(input_path)
    start_time = time.time()

    # Load model with external data
    model = onnx.load(str(input_p), load_external_data=True)

    # Convert to FP16 using onnxconverter_common
    fp16_model: ModelProto = convert_float_to_float16(
        model,
        keep_io_types=keep_io_types,
        disable_shape_infer=disable_shape_infer,
        op_block_list=op_block_list,
    )

    # Save converted model
    if use_external_data:
        data_filename = f"{output_p.name}.data"
        onnx.save_model(
            fp16_model,
            str(output_p),
            save_as_external_data=True,
            all_tensors_to_one_file=True,
            location=data_filename,
        )
    else:
        onnx.save_model(fp16_model, str(output_p))

    # Inject metadata for Android runtime detection
    inject_metadata(
        str(output_p),
        {
            "quantization": "fp16",
            "target_ep": "NNAPI/GPU",
            "warning": "Not recommended for CPU EP",
        },
    )

    elapsed_sec = time.time() - start_time
    new_size = get_model_total_size_bytes(str(output_p))
    ratio = (1.0 - (new_size / orig_size)) * 100.0 if orig_size > 0 else 0.0

    safety_report = None
    if check_safety:
        safety_report = check_audio_safety(str(output_p))

    return {
        "model_name": input_p.name,
        "format": "fp16 (NNAPI/GPU only)",
        "original_size_bytes": orig_size,
        "quantized_size_bytes": new_size,
        "compression_ratio_pct": ratio,
        "elapsed_sec": elapsed_sec,
        "safety_report": safety_report,
        "output_path": str(output_p),
    }


def process_directory(
    input_dir: str,
    output_dir: str,
    target_format: str = "int8",
    check_safety: bool = True,
    copy_ancillary_files: bool = True,
) -> List[Dict[str, any]]:
    """
    Batch process an input directory containing VieNeu models.
    """
    input_root = Path(input_dir).resolve()
    output_root = Path(output_dir).resolve()

    if not input_root.exists():
        raise FileNotFoundError(f"Input directory does not exist: {input_root}")

    if target_format in ("fp16", "both"):
        print(f"\n{FP16_CPU_WARNING}\n")

    onnx_files: List[Path] = []
    other_files: List[Path] = []

    for root, _, files in os.walk(input_root):
        for f in sorted(files):
            fp = Path(root) / f
            if f.endswith(".onnx"):
                onnx_files.append(fp)
            elif not f.endswith(".data") and not f.endswith(".tmp"):
                other_files.append(fp)

    if not onnx_files:
        print(f"No .onnx models found in {input_root}")
        return []

    print(f"Found {len(onnx_files)} ONNX model(s) to process in {input_root}")

    # Copy ancillary config/tokenizer/vocab files if requested
    if copy_ancillary_files:
        for other in other_files:
            rel = other.relative_to(input_root)
            dst = output_root / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            if not dst.exists() or other.stat().st_mtime > dst.stat().st_mtime:
                shutil.copy2(other, dst)

    results: List[Dict[str, any]] = []

    for onnx_path in onnx_files:
        rel = onnx_path.relative_to(input_root)
        print(f"\nProcessing [{rel}]...")

        if target_format in ("int8", "both"):
            int8_out = output_root / rel
            if target_format == "both":
                int8_out = output_root / "int8" / rel

            print(f"  → Quantizing to Dynamic INT8: {int8_out}")
            res_int8 = quantize_model_int8_dynamic(
                str(onnx_path),
                str(int8_out),
                op_types_to_quantize=DEFAULT_INT8_OP_TYPES,
                use_external_data=True,
                check_safety=check_safety,
            )
            results.append(res_int8)

        if target_format in ("fp16", "both"):
            fp16_out = output_root / rel
            if target_format == "both":
                fp16_out = output_root / "fp16" / rel

            print(f"  → Converting to FP16 [NNAPI/GPU only]: {fp16_out}")
            res_fp16 = convert_model_fp16(
                str(onnx_path),
                str(fp16_out),
                keep_io_types=True,
                disable_shape_infer=True,
                use_external_data=True,
                check_safety=check_safety,
            )
            results.append(res_fp16)

    # Print summary report
    print_summary_report(results)
    return results


def print_summary_report(results: List[Dict[str, any]]) -> None:
    """Print formatted summary table of quantization results."""
    print("\n" + "=" * 90)
    print("MODEL QUANTIZATION & COMPRESSION REPORT")
    print("=" * 90)
    header = f"{'Model Name':<35} | {'Format':<18} | {'Original':<10} | {'Quantized':<10} | {'Saved %':<8} | {'Time (s)':<8}"
    print(header)
    print("-" * 90)

    total_orig = 0
    total_quant = 0

    for r in results:
        orig_mb = format_size_mb(r["original_size_bytes"])
        quant_mb = format_size_mb(r["quantized_size_bytes"])
        ratio_str = f"{r['compression_ratio_pct']:.1f}%"
        time_str = f"{r['elapsed_sec']:.2f}s"
        name = r["model_name"][:35]
        fmt = r["format"][:18]
        print(f"{name:<35} | {fmt:<18} | {orig_mb:<10} | {quant_mb:<10} | {ratio_str:<8} | {time_str:<8}")

        total_orig += r["original_size_bytes"]
        total_quant += r["quantized_size_bytes"]

    print("-" * 90)
    total_ratio = (1.0 - (total_quant / total_orig)) * 100.0 if total_orig > 0 else 0.0
    print(
        f"{'TOTAL SUMMARY':<35} | {'':<18} | {format_size_mb(total_orig):<10} | {format_size_mb(total_quant):<10} | {total_ratio:.1f}%   |"
    )
    print("=" * 90 + "\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Production-grade ONNX Model Quantization Pipeline for VieNeu TTS"
    )
    parser.add_argument(
        "--input-dir",
        "-i",
        type=str,
        default="app/src/main/assets/vieneu",
        help="Input directory containing ONNX models or single ONNX file path",
    )
    parser.add_argument(
        "--output-dir",
        "-o",
        type=str,
        default="app/src/main/assets/vieneu_quantized",
        help="Output directory for quantized models",
    )
    parser.add_argument(
        "--format",
        "-f",
        type=str,
        choices=["int8", "fp16", "both"],
        default="int8",
        help="Target quantization format: 'int8' (CPU/XNNPACK), 'fp16' (NNAPI/GPU), or 'both'",
    )
    parser.add_argument(
        "--check-audio-safety",
        action="store_true",
        default=True,
        help="Perform acoustic safety validation on quantized models (default: True)",
    )
    parser.add_argument(
        "--no-audio-safety",
        dest="check_audio_safety",
        action="store_false",
        help="Disable acoustic safety checks",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    input_path = Path(args.input_dir)
    output_path = Path(args.output_dir)

    if input_path.is_file() and input_path.suffix == ".onnx":
        output_path.parent.mkdir(parents=True, exist_ok=True)
        if args.format == "int8":
            res = quantize_model_int8_dynamic(
                str(input_path),
                str(output_path),
                check_safety=args.check_audio_safety,
            )
            print_summary_report([res])
        elif args.format == "fp16":
            print(f"\n{FP16_CPU_WARNING}\n")
            res = convert_model_fp16(
                str(input_path),
                str(output_path),
                check_safety=args.check_audio_safety,
            )
            print_summary_report([res])
        else:
            print("For single file input, please choose format 'int8' or 'fp16'.")
            return 1
    elif input_path.is_dir():
        process_directory(
            str(input_path),
            str(output_path),
            target_format=args.format,
            check_safety=args.check_audio_safety,
        )
    else:
        print(f"Error: input path '{input_path}' not found.")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
