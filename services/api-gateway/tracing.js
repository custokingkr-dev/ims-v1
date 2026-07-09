'use strict';

const { TraceExporter } = require('@google-cloud/opentelemetry-cloud-trace-exporter');
const { getNodeAutoInstrumentations } = require('@opentelemetry/auto-instrumentations-node');
const { NodeSDK } = require('@opentelemetry/sdk-node');

let sdk = null;

function projectId() {
  return process.env.GCP_PROJECT
    || process.env.GOOGLE_CLOUD_PROJECT
    || process.env.GCP_PROJECT_ID
    || '';
}

function tracingEnabled() {
  if (process.env.OTEL_SDK_DISABLED === 'true') return false;
  if ((process.env.OTEL_TRACES_EXPORTER || '').toLowerCase() === 'none') return false;
  if (process.env.NODE_ENV === 'test') return false;
  return Boolean(projectId() || process.env.OTEL_EXPORTER_OTLP_ENDPOINT);
}

function startTracing() {
  if (sdk || !tracingEnabled()) {
    return sdk;
  }

  if (!process.env.OTEL_SERVICE_NAME) {
    process.env.OTEL_SERVICE_NAME = 'api-gateway';
  }

  const exporterOptions = projectId() ? { projectId: projectId() } : {};
  sdk = new NodeSDK({
    traceExporter: new TraceExporter(exporterOptions),
    instrumentations: [
      getNodeAutoInstrumentations({
        '@opentelemetry/instrumentation-fs': { enabled: false },
      }),
    ],
  });

  Promise.resolve(sdk.start()).catch((error) => {
    console.error(JSON.stringify({
      severity: 'ERROR',
      message: 'gateway.tracing.start_failed',
      error: error && error.message ? error.message : String(error),
    }));
  });

  return sdk;
}

async function shutdownTracing() {
  if (!sdk) return;
  const activeSdk = sdk;
  sdk = null;
  await activeSdk.shutdown();
}

if (require.main !== module) {
  startTracing();
}

process.once('SIGTERM', () => {
  shutdownTracing()
    .catch((error) => {
      console.error(JSON.stringify({
        severity: 'ERROR',
        message: 'gateway.tracing.shutdown_failed',
        error: error && error.message ? error.message : String(error),
      }));
    })
    .finally(() => process.exit(0));
});

module.exports = {
  startTracing,
  shutdownTracing,
  tracingEnabled,
  projectId,
};
