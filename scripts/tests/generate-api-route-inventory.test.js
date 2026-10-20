'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { mappingAnnotations } = require('../generate-api-route-inventory');

test('pathless mappings keep their base URL instead of using media or header attributes as paths', () => {
  for (const declaration of [
    '@PostMapping(consumes="application/json")',
    '@GetMapping(produces={"application/json", "text/plain"})',
    '@GetMapping(headers="X-Mode=summary", params="format=json")',
    '@GetMapping(name="student-list")',
    '@GetMapping',
  ]) {
    const [mapping] = mappingAnnotations(declaration);
    assert.deepEqual(mapping.paths, [''], declaration);
  }
});

test('mapping paths accept shorthand and named arrays in any attribute order', () => {
  for (const declaration of [
    '@GetMapping({"/students", "/roster"})',
    '@GetMapping(path={"/students", "/roster"}, produces="application/json")',
    '@GetMapping(produces={"application/json", "text/plain"}, value={"/students", "/roster"})',
    '@GetMapping(headers={"X-Mode=summary", "X-Source=school"}, path={"/students", "/roster"}, params="active=true")',
  ]) {
    const [mapping] = mappingAnnotations(declaration);
    assert.deepEqual(mapping.paths, ['/students', '/roster'], declaration);
  }
});

test('quoted commas and escaped quotes cannot create fake path attributes', () => {
  const [mapping] = mappingAnnotations(String.raw`@GetMapping(
    headers={"X-Meta=a,path=/fake", "X-Quote=a\",path=/also-fake"},
    path="/reports,summary", produces="application/json")`);
  assert.deepEqual(mapping.paths, ['/reports,summary']);
});

test('request mapping methods are independent of path and metadata arguments', () => {
  const [mapping] = mappingAnnotations('@RequestMapping(method={RequestMethod.GET, RequestMethod.POST}, consumes="application/json")');
  assert.deepEqual(mapping.paths, ['']);
  assert.deepEqual(mapping.methods, ['GET', 'POST']);
});

test('provider callback mapping retains its class URL with a pathless JSON POST', () => {
  const mappings = mappingAnnotations(`
    @RequestMapping("/api/v1/notifications/provider-reports/msg91/email")
    class ProviderReportController {
      @PostMapping(consumes="application/json")
      public void report() {}
    }`);
  assert.deepEqual(mappings.map((mapping) => mapping.paths), [
    ['/api/v1/notifications/provider-reports/msg91/email'], [''],
  ]);
  assert.deepEqual(mappings[1].methods, ['POST']);
});

test('an unterminated mapping annotation fails inventory generation', () => {
  assert.throws(() => mappingAnnotations('@PostMapping(path="/students"'), /Unclosed @PostMapping/);
});
