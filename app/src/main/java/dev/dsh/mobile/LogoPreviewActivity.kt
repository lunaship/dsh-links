package dev.dsh.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 临时：方案 A / C 矢量图标 1:1 渲染预览（不依赖 MaterialTheme，纯 Surface）。
 */
class LogoPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFFF5F5F7)
            ) {
                LogoPreviewScreen()
            }
        }
    }
}

@Composable
private fun LogoPreviewScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            "DeepHarness 方案对比",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111827)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            LogoCard(
                label = "A · 鲸鱼 + Harness 环",
                drawableRes = R.drawable.ic_launcher_foreground_whale,
                modifier = Modifier.weight(1f)
            )
            LogoCard(
                label = "C · DH 字标 + 鲸尾",
                drawableRes = R.drawable.ic_launcher_foreground_dh,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "实际尺寸预览（72dp 桌面 launcher 图标大小）",
            fontSize = 14.sp,
            color = Color(0xFF666666)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LauncherIconRealSize(drawableRes = R.drawable.ic_launcher_foreground_whale)
            LauncherIconRealSize(drawableRes = R.drawable.ic_launcher_foreground_dh)
        }
    }
}

@Composable
private fun LogoCard(
    label: String,
    drawableRes: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(62.dp))
                    .background(Color.White)
            ) {
                Image(
                    painter = painterResource(id = drawableRes),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF111827)
        )
    }
}

@Composable
private fun LauncherIconRealSize(drawableRes: Int) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
    ) {
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}