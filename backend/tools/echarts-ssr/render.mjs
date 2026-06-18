// ECharts SSR SVG renderer
// Reads JSON { option, width, height, timeout } from stdin, writes SVG string to stdout.
// No browser, no canvas, no screenshot — pure server-side string rendering via ECharts SSR mode.
import * as echarts from 'echarts';

function readStdin() {
  return new Promise((resolve, reject) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => { data += chunk; });
    process.stdin.on('end', () => resolve(data));
    process.stdin.on('error', reject);
  });
}

async function main() {
  const raw = await readStdin();
  if (!raw || !raw.trim()) {
    console.error('RENDER_ERROR: empty stdin');
    process.exit(2);
  }

  let payload;
  try {
    payload = JSON.parse(raw);
  } catch (e) {
    console.error('RENDER_ERROR: invalid JSON input: ' + e.message);
    process.exit(2);
  }

  const option = payload.option;
  const width = Number.isFinite(payload.width) && payload.width > 0 ? Math.floor(payload.width) : 800;
  const height = Number.isFinite(payload.height) && payload.height > 0 ? Math.floor(payload.height) : 600;

  if (!option || typeof option !== 'object') {
    console.error('RENDER_ERROR: missing option object');
    process.exit(2);
  }

  // SSR SVG mode: init with null canvas, renderer svg, ssr true.
  const chart = echarts.init(null, null, {
    renderer: 'svg',
    ssr: true,
    width,
    height,
  });

  try {
    chart.setOption(option);
    const svg = chart.renderToSVGString();
    process.stdout.write(svg);
  } finally {
    chart.dispose();
  }
}

main().catch((e) => {
  console.error('RENDER_ERROR: ' + (e && e.message ? e.message : String(e)));
  process.exit(1);
});
