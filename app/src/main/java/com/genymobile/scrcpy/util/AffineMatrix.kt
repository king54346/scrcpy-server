package com.genymobile.scrcpy.util

import com.genymobile.scrcpy.device.Point
import com.genymobile.scrcpy.device.Size
import kotlin.math.cos
import kotlin.math.sin

/**
 * Represents a 2D affine transform (a 3x3 matrix):
 *
 * <pre>
 * / a c e \
 * | b d f |
 * \ 0 0 1 /
</pre> *
 *
 *
 * Or, a 4x4 matrix if we add a z axis:
 *
 * <pre>
 * / a c 0 e \
 * | b d 0 f |
 * | 0 0 1 0 |
 * \ 0 0 0 1 /
</pre> *
 */
class AffineMatrix
/**
 * Create a new matrix:
 *
 * <pre>
 * / a c e \
 * | b d f |
 * \ 0 0 1 /
</pre> *
 */(
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val d: Double,
    private val e: Double,
    private val f: Double
) {
    override fun toString(): String {
        return "[$a, $c, $e; $b, $d, $f]"
    }

    /**
     * Apply the transform to a point (`this` should be a matrix converted to pixels coordinates via [.ndcToPixels]).
     *
     * @param point the source point
     * @return the converted point
     */
    fun apply(point: Point): Point {
        val x = point.x
        val y = point.y
        val xx = (a * x + c * y + e).toInt()
        val yy = (b * x + d * y + f).toInt()
        return Point(xx, yy)
    }

    /**
     * Compute `this * rhs`.
     *
     * @param rhs the matrix to multiply
     * @return the product
     */
    fun multiply(rhs: AffineMatrix?): AffineMatrix {
        if (rhs == null) {
            // For convenience
            return this
        }

        val aa = this.a * rhs.a + this.c * rhs.b
        val bb = this.b * rhs.a + this.d * rhs.b
        val cc = this.a * rhs.c + this.c * rhs.d
        val dd = this.b * rhs.c + this.d * rhs.d
        val ee = this.a * rhs.e + this.c * rhs.f + this.e
        val ff = this.b * rhs.e + this.d * rhs.f + this.f
        return AffineMatrix(aa, bb, cc, dd, ee, ff)
    }

    /**
     * Invert the matrix.
     *
     * @return the inverse matrix (or `null` if not invertible).
     */
    fun invert(): AffineMatrix? {
        // The 3x3 matrix M can be decomposed into M = M1 * M2:
        //         M1          M2
        //      / 1 0 e \   / a c 0 \
        //      | 0 1 f | * | b d 0 |
        //      \ 0 0 1 /   \ 0 0 1 /
        //
        // The inverse of an invertible 2x2 matrix is given by this formula:
        //
        //      / A B \⁻¹     1   /  D -B \
        //      \ C D /   = ----- \ -C  A /
        //                  AD-BC
        //
        // Let B=c and C=b (to apply the general formula with the same letters).
        //
        //     M⁻¹ = (M1 * M2)⁻¹ = M2⁻¹ * M1⁻¹
        //
        //                  M2⁻¹              M1⁻¹
        //           /----------------\
        //             1   /  d -B  0 \   / 1  0 -e \
        //         = ----- | -C  a  0 | * | 0  1 -f |
        //           ad-BC \  0  0  1 /   \ 0  0  1 /
        //
        // With the original letters:
        //
        //             1   /  d -c  0 \   / 1  0 -e \
        //     M⁻¹ = ----- | -b  a  0 | * | 0  1 -f |
        //           ad-cb \  0  0  1 /   \ 0  0  1 /
        //
        //             1   /  d -c  cf-de \
        //         = ----- | -b  a  be-af |
        //           ad-cb \  0  0    1   /

        val det = a * d - c * b
        if (det == 0.0) {
            // Not invertible
            return null
        }

        val aa = d / det
        val bb = -b / det
        val cc = -c / det
        val dd = a / det
        val ee = (c * f - d * e) / det
        val ff = (b * e - a * f) / det

        return AffineMatrix(aa, bb, cc, dd, ee, ff)
    }

    /**
     * Return this transform applied from the center (0.5, 0.5).
     *
     * @return the resulting matrix
     */
    fun fromCenter(): AffineMatrix {
        return translate(0.5, 0.5).multiply(this).multiply(translate(-0.5, -0.5))
    }

    /**
     * Return this transform with the specified aspect ratio.
     *
     * @param ar the aspect ratio
     * @return the resulting matrix
     */
    fun withAspectRatio(ar: Double): AffineMatrix {
        return scale(1 / ar, 1.0).multiply(this).multiply(scale(ar, 1.0))
    }

    /**
     * Return this transform with the specified aspect ratio.
     *
     * @param size the size describing the aspect ratio
     * @return the transform
     */
    fun withAspectRatio(size: Size): AffineMatrix {
        val ar = size.width.toDouble() / size.height
        return withAspectRatio(ar)
    }

    /**
     * Export this affine transform to a 4x4 column-major order matrix.
     *
     * @param matrix output 4x4 matrix
     */
    fun to4x4(matrix: FloatArray) {
        // matrix is a 4x4 matrix in column-major order

        // Column 0

        matrix[0] = a.toFloat()
        matrix[1] = b.toFloat()
        matrix[2] = 0f
        matrix[3] = 0f

        // Column 1
        matrix[4] = c.toFloat()
        matrix[5] = d.toFloat()
        matrix[6] = 0f
        matrix[7] = 0f

        // Column 2
        matrix[8] = 0f
        matrix[9] = 0f
        matrix[10] = 1f
        matrix[11] = 0f

        // Column 3
        matrix[12] = e.toFloat()
        matrix[13] = f.toFloat()
        matrix[14] = 0f
        matrix[15] = 1f
    }

    /**
     * Export this affine transform to a 4x4 column-major order matrix.
     *
     * @return 4x4 matrix
     */
    fun to4x4(): FloatArray {
        val matrix = FloatArray(16)
        to4x4(matrix)
        return matrix
    }

    companion object {
        /**
         * The identity matrix.
         */
        val IDENTITY: AffineMatrix = AffineMatrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        /**
         * Return a matrix which converts from Normalized Device Coordinates to pixels.
         *
         * @param size the target size
         * @return the transform matrix
         */
        fun ndcFromPixels(size: Size): AffineMatrix {
            val w = size.width.toDouble()
            val h = size.height.toDouble()
            return AffineMatrix(1 / w, 0.0, 0.0, -1 / h, 0.0, 1.0)
        }

        /**
         * Return a matrix which converts from pixels to Normalized Device Coordinates.
         *
         * @param size the source size
         * @return the transform matrix
         */
        fun ndcToPixels(size: Size): AffineMatrix {
            val w = size.width.toDouble()
            val h = size.height.toDouble()
            return AffineMatrix(w, 0.0, 0.0, -h, 0.0, h)
        }

        /**
         * Multiply all matrices from left to right, ignoring any `null` matrix (for convenience).
         *
         * @param matrices the matrices
         * @return the product
         */
        fun multiplyAll(vararg matrices: AffineMatrix?): AffineMatrix? {
            var result: AffineMatrix? = null
            for (matrix in matrices) {
                result = result?.multiply(matrix) ?: matrix
            }
            return result
        }

        /**
         * Return a translation matrix.
         *
         * @param x the horizontal translation
         * @param y the vertical translation
         * @return the matrix
         */
        fun translate(x: Double, y: Double): AffineMatrix {
            return AffineMatrix(1.0, 0.0, 0.0, 1.0, x, y)
        }

        /**
         * Return a scaling matrix.
         *
         * @param x the horizontal scaling
         * @param y the vertical scaling
         * @return the matrix
         */
        fun scale(x: Double, y: Double): AffineMatrix {
            return AffineMatrix(x, 0.0, 0.0, y, 0.0, 0.0)
        }

        /**
         * Return a scaling matrix.
         *
         * @param from the source size
         * @param to   the destination size
         * @return the matrix
         */
        fun scale(from: Size, to: Size): AffineMatrix {
            val scaleX = to.width.toDouble() / from.width
            val scaleY = to.height.toDouble() / from.height
            return scale(scaleX, scaleY)
        }

        /**
         * Return a matrix applying a "reframing" (cropping a rectangle).
         *
         *
         * `(x, y)` is the bottom-left corner, `(w, h)` is the size of the rectangle.
         *
         * @param x horizontal coordinate (increasing to the right)
         * @param y vertical coordinate (increasing upwards)
         * @param w width
         * @param h height
         * @return the matrix
         */
        fun reframe(x: Double, y: Double, w: Double, h: Double): AffineMatrix {
            require(!(w == 0.0 || h == 0.0)) { "Cannot reframe to an empty area: " + w + "x" + h }
            return scale(1 / w, 1 / h).multiply(translate(-x, -y))
        }

        /**
         * Return an orthogonal rotation matrix.
         *
         * @param ccwRotation the counter-clockwise rotation
         * @return the matrix
         */
        fun rotateOrtho(ccwRotation: Int): AffineMatrix {
            return when (ccwRotation) {
                0 -> IDENTITY
                1 ->                 // 90° counter-clockwise
                    AffineMatrix(0.0, 1.0, -1.0, 0.0, 1.0, 0.0)

                2 ->                 // 180°
                    AffineMatrix(-1.0, 0.0, 0.0, -1.0, 1.0, 1.0)

                3 ->                 // 90° clockwise
                    AffineMatrix(0.0, -1.0, 1.0, 0.0, 0.0, 1.0)

                else -> throw IllegalArgumentException("Invalid rotation: $ccwRotation")
            }
        }

        /**
         * Return an horizontal flip matrix.
         *
         * @return the matrix
         */
        fun hflip(): AffineMatrix {
            return AffineMatrix(-1.0, 0.0, 0.0, 1.0, 1.0, 0.0)
        }

        /**
         * Return a vertical flip matrix.
         *
         * @return the matrix
         */
        fun vflip(): AffineMatrix {
            return AffineMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 1.0)
        }

        /**
         * Return a rotation matrix.
         *
         * @param ccwDegrees the angle, in degrees (counter-clockwise)
         * @return the matrix
         */
        fun rotate(ccwDegrees: Double): AffineMatrix {
            val radians = Math.toRadians(ccwDegrees)
            val cos = cos(radians)
            val sin = sin(radians)
            return AffineMatrix(cos, sin, -sin, cos, 0.0, 0.0)
        }
    }
}