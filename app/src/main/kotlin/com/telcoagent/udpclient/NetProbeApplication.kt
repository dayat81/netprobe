package com.telcoagent.udpclient

import android.app.Application
import android.util.Log
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import java.util.concurrent.TimeUnit

class NetProbeApplication : Application() {

    companion object {
        private const val TAG = "NetProbeOTel"

        // OTLP endpoint — via proxy on appium VM (bypass firewall)
        const val OTLP_ENDPOINT = "http://34.21.210.194:8080"
        // =========================================================

        private var otelSdk: OpenTelemetrySdk? = null

        fun getTracer(): Tracer? = otelSdk?.getTracer("netprobe-android")
        fun getMeter(): Meter? = otelSdk?.getMeter("netprobe-android")
    }

    override fun onCreate() {
        super.onCreate()
        initTelemetry()
    }

    private fun initTelemetry() {
        try {
            val resource = Resource.getDefault().merge(
                Resource.create(Attributes.of(
                    AttributeKey.stringKey("service.name"), "netprobe-android",
                    AttributeKey.stringKey("app.version"), BuildConfig.VERSION_NAME,
                    AttributeKey.stringKey("app.build"), BuildConfig.VERSION_CODE.toString()
                ))
            )

            // Traces
            val spanExporter = OtlpHttpSpanExporter.builder()
                .setEndpoint("$OTLP_ENDPOINT/v1/traces")
                .build()
            val tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .build()

            // Metrics
            val metricExporter = OtlpHttpMetricExporter.builder()
                .setEndpoint("$OTLP_ENDPOINT/v1/metrics")
                .build()
            val meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter).build())
                .build()

            otelSdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .buildAndRegisterGlobal()

            // Record app startup
            getTracer()?.spanBuilder("app.startup")?.startSpan()?.end()
            Log.i(TAG, "OTel initialized → $OTLP_ENDPOINT")
        } catch (e: Exception) {
            Log.e(TAG, "OTel init failed", e)
        }
    }

    fun recordVpnEvent(event: String, attrs: Map<String, String> = emptyMap()) {
        try {
            val span = getTracer()?.spanBuilder("vpn.$event")?.apply {
                attrs.forEach { (k, v) -> setAttribute(AttributeKey.stringKey(k), v) }
            }?.startSpan()
            span?.end()
            getMeter()?.counterBuilder("vpn.events")?.build()
                ?.add(1, Attributes.of(AttributeKey.stringKey("event"), event))
        } catch (e: Exception) { Log.w(TAG, "OTel vpn event fail", e) }
    }

    fun recordSplitTunnel(action: String, appCount: Int) {
        try {
            getMeter()?.counterBuilder("split_tunnel.actions")?.build()
                ?.add(1, Attributes.of(
                    AttributeKey.stringKey("action"), action,
                    AttributeKey.longKey("app_count"), appCount.toLong()
                ))
        } catch (e: Exception) { Log.w(TAG, "OTel split tunnel fail", e) }
    }

    fun recordNetworkMetric(metric: String, value: Double, type: String = "cellular") {
        try {
            getMeter()?.gaugeBuilder("network.$metric")?.build()
                ?.set(value, Attributes.of(AttributeKey.stringKey("type"), type))
        } catch (e: Exception) { Log.w(TAG, "OTel metric fail", e) }
    }

    override fun onTerminate() {
        otelSdk?.close()
        super.onTerminate()
    }
}
