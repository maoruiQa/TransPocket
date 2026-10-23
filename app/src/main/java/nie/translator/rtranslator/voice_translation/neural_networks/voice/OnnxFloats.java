package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;

/** Reads an ONNX tensor as float32. INT8 graphs sometimes return float16 activations. */
final class OnnxFloats {
    private OnnxFloats() {
    }

    static float[] toFloats(OnnxTensor tensor) throws OrtException {
        long[] shape = tensor.getInfo().getShape();
        int count = count(shape);
        OnnxJavaType type = tensor.getInfo().type;
        if (type == OnnxJavaType.FLOAT) {
            FloatBuffer buffer = tensor.getFloatBuffer();
            float[] data = new float[buffer.remaining()];
            buffer.get(data);
            return data;
        }
        if (type == OnnxJavaType.FLOAT16) {
            ByteBuffer raw = tensor.getByteBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            raw.position(0);
            float[] data = new float[count];
            for (int i = 0; i < count; i++) {
                data[i] = halfToFloat(raw.getShort() & 0xffff);
            }
            return data;
        }
        throw new OrtException("ONNX tensor type is not float: " + type);
    }

    private static int count(long[] shape) throws OrtException {
        int count = 1;
        for (long dim : shape) {
            if (dim < 0 || dim > Integer.MAX_VALUE) {
                throw new OrtException("ONNX tensor shape is not concrete");
            }
            try {
                count = Math.multiplyExact(count, (int) dim);
            } catch (ArithmeticException overflow) {
                throw new OrtException("ONNX tensor is too large");
            }
        }
        return count;
    }

    private static float halfToFloat(int bits) {
        int sign = (bits & 0x8000) << 16;
        int exp = (bits >> 10) & 0x1f;
        int mant = bits & 0x3ff;
        if (exp == 0) {
            if (mant == 0) {
                return Float.intBitsToFloat(sign);
            }
            exp = 1;
            while ((mant & 0x400) == 0) {
                mant <<= 1;
                exp--;
            }
            mant &= 0x3ff;
            int outExp = exp + (127 - 15);
            return Float.intBitsToFloat(sign | (outExp << 23) | (mant << 13));
        }
        if (exp == 31) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mant << 13));
        }
        return Float.intBitsToFloat(sign | ((exp + (127 - 15)) << 23) | (mant << 13));
    }
}
