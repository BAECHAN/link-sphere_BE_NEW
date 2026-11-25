package com.example.linksphere.domain.post

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * og 이미지 원본을 피드 카드용 사본으로 줄인다(docs/plans/2026-11-25-og-thumbnail.md "크기 규칙"·"형식"·"받기 상한"). 네트워크와
 * 저장은 [OgThumbnailService]가 맡고 여기는 바이트만 다룬다.
 *
 * - 크기: 카드가 16:9 가운데를 잘라 그리므로 960×540을 덮는 최소 크기로 줄인다(`max(960/가로, 540/세로)` 배율). 작은 원본은 키우지 않는다.
 * - 형식: 실제로 투명한 픽셀이 하나라도 있으면 PNG, 아니면 JPEG 품질 0.8. 원본 형식과 상관없이 늘 다시 인코딩한다.
 * - 픽셀 상한: 머리(헤더)에 적힌 가로×세로를 픽셀을 풀기 전에 본다. 작은 파일이 거대한 그림으로 풀리는 압축 폭탄을 막는다.
 *
 * AWT 그래픽(BufferedImage·Graphics2D)만 쓰고 글꼴·화면(Toolkit)은 쓰지 않는다. 화면 없는 환경(headless)에서 돈다 - Spring Boot가
 * 시작할 때 `java.awt.headless`를 따로 정하지 않았으면 true로 넣는다(3.5.16 SpringApplication.configureHeadlessProperty).
 */
internal object OgThumbnailResizer {

    private const val TARGET_WIDTH = 960
    private const val TARGET_HEIGHT = 540

    // 5,000만 픽셀이면 ARGB로 풀어 약 200MB다. Lambda 메모리 2,048MB 안이다
    private const val MAX_PIXELS = 50_000_000L

    private const val JPEG_QUALITY = 0.8f

    sealed interface Result

    class Resized(val bytes: ByteArray, val extension: String, val contentType: String) : Result

    data class Rejected(val reason: String) : Result

    fun resize(source: ByteArray): Result {
        val decoded =
            MemoryCacheImageInputStream(ByteArrayInputStream(source)).use { input ->
                // WBMP 읽기는 머리가 거의 없어 아무 바이트나 자기 형식이라고 답한다 - 사이트가 WBMP를 og 이미지로 쓰는 일은 없다
                val reader =
                    ImageIO.getImageReaders(input).asSequence().firstOrNull { it.formatName.lowercase() != "wbmp" }
                        ?: return Rejected("unsupported-format")

                try {
                    reader.input = input

                    if (reader.getWidth(0).toLong() * reader.getHeight(0) > MAX_PIXELS) {
                        return Rejected("too-many-pixels")
                    }

                    reader.read(0)
                } catch (e: Exception) {
                    // 일부 CMYK JPEG·잘린 파일은 머리는 읽혀도 본문에서 실패한다
                    return Rejected("decode-failed")
                } finally {
                    reader.dispose()
                }
            }

        val transparent = hasTransparentPixel(decoded)
        val scaled = scale(decoded, if (transparent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)

        return if (transparent) {
            Resized(encodePng(scaled), "png", "image/png")
        } else {
            Resized(encodeJpeg(scaled), "jpg", "image/jpeg")
        }
    }

    private fun hasTransparentPixel(image: BufferedImage): Boolean {
        if (!image.colorModel.hasAlpha()) {
            return false
        }

        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            image.getRGB(0, y, image.width, 1, row, 0, image.width)

            if (row.any { it ushr 24 != 0xFF }) {
                return true
            }
        }

        return false
    }

    /**
     * 한 번에 많이 줄이면 쌍선형 보간이 픽셀을 건너뛰어 글씨가 깨진다. 절반씩 줄여 목표에 가까워진 뒤 마지막에 목표 크기로 맞춘다
     * (Image.getScaledInstance의 느린 평균 대신 흔히 쓰는 방식).
     */
    private fun scale(source: BufferedImage, type: Int): BufferedImage {
        val ratio = min(1.0, max(TARGET_WIDTH.toDouble() / source.width, TARGET_HEIGHT.toDouble() / source.height))
        val width = max(1, (source.width * ratio).roundToInt())
        val height = max(1, (source.height * ratio).roundToInt())

        var current = source
        while (current.width / 2 >= width && current.height / 2 >= height) {
            current = draw(current, current.width / 2, current.height / 2, type)
        }

        // 줄이지 않은 원본도 한 번은 그린다 - 색 모델(팔레트·회색조·투명 채널)을 인코딩할 형식에 맞춘다. JPEG 쓰기는 투명 채널을 받지 못한다
        if (current === source || current.width != width || current.height != height) {
            current = draw(current, width, height, type)
        }

        return current
    }

    private fun draw(source: BufferedImage, width: Int, height: Int, type: Int): BufferedImage {
        val target = BufferedImage(width, height, type)
        val graphics = target.createGraphics()

        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }

        return target
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = JPEG_QUALITY
            }

        return write(writer, image, param)
    }

    private fun encodePng(image: BufferedImage): ByteArray = write(ImageIO.getImageWritersByFormatName("png").next(), image, null)

    private fun write(writer: ImageWriter, image: BufferedImage, param: ImageWriteParam?): ByteArray {
        val bytes = ByteArrayOutputStream()

        try {
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }

        return bytes.toByteArray()
    }
}
