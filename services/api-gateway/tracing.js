'use strict';

const { getNodeAutoInstrumentations } = require('@opentelemetry/auto-instrumentations-node');
const { OTLPTraceExporter } = require('@opentelemetry/exporter-trace-otlp-proto');
const { defaultResource, resourceFromAttributes } = require('@opentelemetry/resources');
const { NodeSDK } = require('@opentelemetry/sdk-node');
const { BatchSpanProcessor } = require('@opentelemetry/sdk-trace-base');
const { GoogleAuth } = require('google-auth-library');

let sdk = null;
let spanProcessor = null;

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

function tracesEndpoint() {
  if (process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT) {
    return process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT;
  }
  const endpoint = process.env.OTEL_EXPORTER_OTLP_ENDPOINT || 'https://telemetry.googleapis.com';
  return `${endpoint.replace(/\/$/, '')}/v1/traces`;
}

function startTracing() {
  if (sdk || !tracingEnabled()) {
    return sdk;
  }

  if (!process.env.OTEL_SERVICE_NAME) {
    process.env.OTEL_SERVICE_NAME = 'api-gateway';
  }

  const authenticatedClient = new GoogleAuth({
    scopes: 'https://www.googleapis.com/auth/cloud-platform',
  }).getClient();
  const exporter = new OTLPTraceExporter({
    url: tracesEndpoint(),
    async headers() {
      const rawHeaders = await (await authenticatedClient).getRequestHeaders();
      return typeof rawHeaders.entries === 'function'
        ? Object.fromEntries(rawHeaders.entries())
        : rawHeaders;
    },
  });
  spanProcessor = new BatchSpanProcessor(exporter, {
    exportTimeoutMillis: 4000,
  });
  sdk = new NodeSDK({
    // Preserve the release metadata explicitly so Trace Explorer can filter this gateway by
    // service, environment, and deployed version like the Java services.
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
  tracesEndpoint,
};
