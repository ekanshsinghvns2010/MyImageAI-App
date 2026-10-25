package ai.myimage.provider

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Development/testing provider. It does NOT run any AI model: it draws a striped placeholder labelled
 * "SIMULATED - NOT AI GENERATED". Jobs completed with it are reported with simulated=true.
 */
class MockProvider : ImageGenerationProvider {
    override val name = "mock"
    override val simulated = true

    override fun generate(request: ProviderRequest): ProviderResult {
        val images = (0 until request.numImages).map { i ->
            val seed = request.seed?.plus(i)
            ProviderImage(render(request.width, request.height, seed ?: i.toLong()), "image/png", seed)
        }
        return ProviderResult(images, simulated = true)
    }

    private fun render(w: Int, h: Int, seed: Long): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(40, 40, 48)
            g.fillRect(0, 0, w, h)
            // Diagonal warning stripes so the image is obviously a placeholder even if text rendering is unavailable.
            g.color = Color(200, 150, 30)
            val step = 48
            var x = -h
            while (x < w) {
                g.drawLine(x, 0, x + h, h)
                g.drawLine(x + 1, 0, x + 1 + h, h)
                x += step
            }
            try {
                g.color = Color.WHITE
                g.font = Font(Font.SANS_SERIF, Font.BOLD, (w / 16).coerceAtLeast(12))
                g.drawString("SIMULATED - NOT AI GENERATED", 12, h / 2)
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, (w / 32).coerceAtLeast(10))
                g.drawString("mock provider, seed $seed", 12, h / 2 + w / 14)
            } catch (t: Throwable) {
                // No fonts installed in this JVM: the stripes alone still mark the image as a placeholder.
            }
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    override fun health() = ProviderHealth(true, "mock provider (simulated output)")
}
