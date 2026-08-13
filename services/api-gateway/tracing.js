'use strict';

const { TraceExporter } = require('@google-cloud/opentelemetry-cloud-trace-exporter');
const { getNodeAutoInstrumentations } = require('@opentelemetry/auto-instrumentations-node');
const { defaultResource, resourceFromAttributes } = require('@opentelemetry/resources');
const { NodeSDK } = require('@opentelemetry/sdk-node');
const { BatchSpanProcessor } = require('@opentelemetry/sdk-trace-base');

let sdk = null;
let spanProcessor = null;
const TRACE_RESOURCE_FILTER = /^(service\.(name|version)|deployment\.environment\.name)$/;

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

function configuredResourceAttributes(rawAttributes = process.env.OTEL_RESOURCE_ATTRIBUTES || '') {
  const attributes = {};
  for (const entry of rawAttributes.split(',')) {
    const separator = entry.indexOf('=');
    if (separator <= 0) continue;
    const key = entry.slice(0, separator).trim();
    const value = entry.slice(separator + 1).trim();
    if (key && value) attributes[key] = value;
  }
  attributes['service.name'] = process.env.OTEL_SERVICE_NAME || 'api-gateway';
  if (projectId() && !attributes['gcp.project_id']) {
    attributes['gcp.project_id'] = projectId();
  }
  return attributes;
}

function startTracing() {
  if (sdk || !tracingEnabled()) {
    return sdk;
  }

  if (!process.env.OTEL_SERVICE_NAME) {
    process.env.OTEL_SERVICE_NAME = 'api-gateway';
  }

  const exporterOptions = {
    ...(projectId() ? { projectId: projectId() } : {}),
    // The Google exporter intentionally drops non-monitored-resource attributes unless selected.
    resourceFilter: TRACE_RESOURCE_FILTER,
  };
  spanProcessor = new BatchSpanProcessor(new TraceExporter(exporterOptions), {
    exportTimeoutMillis: 4000,
  });
  sdk = new NodeSDK({
    // TraceExporter enriches spans with the detected Cloud Run resource, but does not preserve
    // the declarative OTEL resource variables reliably. Merge them explicitly so Trace Explorer
    // can filter this gateway by service, environment, and deployed version like the Java services.
    resource: defaultResource().merge(resourceFromAttributes(configuredResourceAttributes())),
    spanProcessors: [spanProcessor],
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

async function flushTracing() {
  if (!spanProcessor) return;
  await spanProcessor.forceFlush();
}

async function shutdownTracing() {
  if (!sdk) return;
  const activeSdk = sdk;
  sdk = null;
  try {
    await activeSdk.shutdown();
  } finally {
    spanProcessor = null;
  }
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
  flushTracing,
  shutdownTracing,
  tracingEnabled,
  projectId,
  configuredResourceAttributes,
  TRACE_RESOURCE_FILTER,
};
