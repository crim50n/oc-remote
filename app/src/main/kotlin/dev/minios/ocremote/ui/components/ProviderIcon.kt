package dev.minios.ocremote.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R

/**
 * Displays a provider icon for the given provider ID.
 * Falls back to the "synthetic" (sparkle) icon for unknown providers.
 *
 * Icons are sourced from models.dev and bundled as vector drawables.
 * They use `android:tint="?attr/colorControlNormal"` so the tint adapts
 * to the current theme automatically. We additionally apply a color filter
 * to match the surrounding text color.
 */
@Composable
fun ProviderIcon(
    providerId: String,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    @DrawableRes val resId = providerIconMap[providerId] ?: R.drawable.ic_provider_synthetic

    Image(
        painter = painterResource(id = resId),
        contentDescription = providerId,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint)
    )
}

/**
 * Returns the drawable resource ID for a provider, or null if not bundled.
 */
@DrawableRes
fun getProviderIconRes(providerId: String): Int {
    return providerIconMap[providerId] ?: R.drawable.ic_provider_synthetic
}

/**
 * Mapping from provider ID to drawable resource.
 * Generated from models.dev provider icons.
 */
private val providerIconMap = mapOf(
    "abacus" to R.drawable.ic_provider_abacus,
    "aihubmix" to R.drawable.ic_provider_aihubmix,
    "alibaba-cn" to R.drawable.ic_provider_alibaba_cn,
    "alibaba" to R.drawable.ic_provider_alibaba,
    "amazon-bedrock" to R.drawable.ic_provider_amazon_bedrock,
    "anthropic" to R.drawable.ic_provider_anthropic,
    "azure-cognitive-services" to R.drawable.ic_provider_azure_cognitive_services,
    "azure" to R.drawable.ic_provider_azure,
    "bailing" to R.drawable.ic_provider_bailing,
    "baseten" to R.drawable.ic_provider_baseten,
    "cerebras" to R.drawable.ic_provider_cerebras,
    "chutes" to R.drawable.ic_provider_chutes,
    "cloudflare-ai-gateway" to R.drawable.ic_provider_cloudflare_ai_gateway,
    "cloudflare-workers-ai" to R.drawable.ic_provider_cloudflare_workers_ai,
    "cohere" to R.drawable.ic_provider_cohere,
    "cortecs" to R.drawable.ic_provider_cortecs,
    "deepinfra" to R.drawable.ic_provider_deepinfra,
    "deepseek" to R.drawable.ic_provider_deepseek,
    "fastrouter" to R.drawable.ic_provider_fastrouter,
    "fireworks-ai" to R.drawable.ic_provider_fireworks_ai,
    "friendli" to R.drawable.ic_provider_friendli,
    "github-copilot" to R.drawable.ic_provider_github_copilot,
    "github-models" to R.drawable.ic_provider_github_models,
    "google-vertex-anthropic" to R.drawable.ic_provider_google_vertex_anthropic,
    "google-vertex" to R.drawable.ic_provider_google_vertex,
    "google" to R.drawable.ic_provider_google,
    "groq" to R.drawable.ic_provider_groq,
    "helicone" to R.drawable.ic_provider_helicone,
    "huggingface" to R.drawable.ic_provider_huggingface,
    "iflowcn" to R.drawable.ic_provider_iflowcn,
    "inception" to R.drawable.ic_provider_inception,
    "inference" to R.drawable.ic_provider_inference,
    "io-net" to R.drawable.ic_provider_io_net,
    "kimi-for-coding" to R.drawable.ic_provider_kimi_for_coding,
    "llama" to R.drawable.ic_provider_llama,
    "lmstudio" to R.drawable.ic_provider_lmstudio,
    "minimax-cn" to R.drawable.ic_provider_minimax_cn,
    "minimax" to R.drawable.ic_provider_minimax,
    "mistral" to R.drawable.ic_provider_mistral,
    "modelscope" to R.drawable.ic_provider_modelscope,
    "moonshotai-cn" to R.drawable.ic_provider_moonshotai_cn,
    "moonshotai" to R.drawable.ic_provider_moonshotai,
    "morph" to R.drawable.ic_provider_morph,
    "nano-gpt" to R.drawable.ic_provider_nano_gpt,
    "nebius" to R.drawable.ic_provider_nebius,
    "nvidia" to R.drawable.ic_provider_nvidia,
    "ollama-cloud" to R.drawable.ic_provider_ollama_cloud,
    "openai" to R.drawable.ic_provider_openai,
    "opencode" to R.drawable.ic_provider_opencode,
    "openrouter" to R.drawable.ic_provider_openrouter,
    "ovhcloud" to R.drawable.ic_provider_ovhcloud,
    "perplexity" to R.drawable.ic_provider_perplexity,
    "poe" to R.drawable.ic_provider_poe,
    "requesty" to R.drawable.ic_provider_requesty,
    "sap-ai-core" to R.drawable.ic_provider_sap_ai_core,
    "scaleway" to R.drawable.ic_provider_scaleway,
    "siliconflow-cn" to R.drawable.ic_provider_siliconflow_cn,
    "siliconflow" to R.drawable.ic_provider_siliconflow,
    "submodel" to R.drawable.ic_provider_submodel,
    "synthetic" to R.drawable.ic_provider_synthetic,
    "togetherai" to R.drawable.ic_provider_togetherai,
    "upstage" to R.drawable.ic_provider_upstage,
    "v0" to R.drawable.ic_provider_v0,
    "venice" to R.drawable.ic_provider_venice,
    "vercel" to R.drawable.ic_provider_vercel,
    "vultr" to R.drawable.ic_provider_vultr,
    "wandb" to R.drawable.ic_provider_wandb,
    "xai" to R.drawable.ic_provider_xai,
    "xiaomi" to R.drawable.ic_provider_xiaomi,
    "zai-coding-plan" to R.drawable.ic_provider_zai_coding_plan,
    "zai" to R.drawable.ic_provider_zai,
    "zenmux" to R.drawable.ic_provider_zenmux,
    "zhipuai-coding-plan" to R.drawable.ic_provider_zhipuai_coding_plan,
    "zhipuai" to R.drawable.ic_provider_zhipuai
)
