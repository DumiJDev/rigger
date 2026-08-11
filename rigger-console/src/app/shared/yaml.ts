/**
 * Renders a resource spec back as YAML.
 *
 * <p>Deliberately narrow: it covers exactly the shapes `ResourceResponse.spec` can hold — the JSON
 * Jackson produced from our own domain records, so nested maps, arrays of scalars, arrays of
 * objects, strings, numbers, booleans and null. It is **not** a general YAML emitter: no anchors,
 * no tags, no non-string keys, no cyclic references. A full emitter is a dependency (js-yaml is
 * ~40 kB) for a read-only panel, and this console must build with nothing fetched at runtime.
 *
 * <p>The one property that matters is faithfulness — an operator reads this to decide what to
 * change, so a value that renders differently from what the server holds is worse than no panel at
 * all. Every ambiguous case therefore falls back to quoting rather than guessing: a string that
 * would re-parse as a number, a boolean, null, or a YAML indicator is always quoted.
 */
export function toYaml(value: unknown): string {
  if (!isContainer(value)) return scalar(value);
  if (isEmpty(value)) return Array.isArray(value) ? '[]' : '{}';
  return render(value, '').join('\n');
}

/** Two spaces per level. Tabs are not legal YAML indentation. */
const INDENT = '  ';

function render(node: object, pad: string): string[] {
  return Array.isArray(node)
    ? node.flatMap((item) => itemLines(item, pad))
    : Object.entries(node)
        // `undefined` has no YAML spelling; JSON never produces it, but a spec assembled in the
        // console (an optimistic scale update, say) can carry it. Dropping is closer than `null`.
        .filter(([, v]) => v !== undefined)
        .flatMap(([key, v]) => entryLines(key, v, pad));
}

function entryLines(key: string, value: unknown, pad: string): string[] {
  const k = `${pad}${scalarKey(key)}:`;
  if (isContainer(value) && !isEmpty(value)) {
    // Sequence dashes sit at the parent key's own indent — legal, and what every hand-written
    // manifest in this repo looks like, so a copied block pastes back unchanged.
    const childPad = Array.isArray(value) ? pad : pad + INDENT;
    return [k, ...render(value, childPad)];
  }
  return blockOrInline(k, value, pad);
}

function itemLines(value: unknown, pad: string): string[] {
  if (isContainer(value) && !isEmpty(value)) {
    const inner = render(value, pad + INDENT);
    // `- ` replaces the first item's indent so the dash shares its line, instead of a bare `-`.
    inner[0] = `${pad}- ${inner[0].slice(pad.length + INDENT.length)}`;
    return inner;
  }
  return blockOrInline(`${pad}-`, value, pad);
}

/** Emits `<prefix> <scalar>`, or a block scalar over several lines when the string is multi-line. */
function blockOrInline(prefix: string, value: unknown, pad: string): string[] {
  if (typeof value === 'string' && value.includes('\n') && blockSafe(value)) {
    // Chomping is the whole game here: a config file's contents differ by the exact trailing
    // newlines, and the default `|` clips them to one. `|-` strips, `|+` keeps every one.
    const trailing = /\n*$/.exec(value)![0].length;
    const header = trailing === 0 ? '|-' : trailing === 1 ? '|' : '|+';
    // Every block line contributes its own newline, so the final one is already implied.
    const body = trailing === 0 ? value : value.slice(0, -1);
    return [
      `${prefix} ${header}`,
      // A blank line must stay genuinely blank; indenting it would add whitespace to the content.
      ...body.split('\n').map((l) => (l === '' ? '' : `${pad}${INDENT}${l}`)),
    ];
  }
  return [`${prefix} ${scalar(value)}`];
}

/**
 * A block scalar cannot express a line with trailing whitespace (invisible, and readers strip it)
 * nor a first line that is indented further than the block itself without an explicit indentation
 * indicator. Those go out double-quoted instead — uglier, exact.
 */
function blockSafe(value: string): boolean {
  const lines = value.split('\n');
  return (
    !lines.some((l) => /\s$/.test(l) || l.startsWith('\t')) &&
    !/^\s/.test(lines[0]) &&
    // Any control character other than the newlines being split on (tab is legal mid-line).
    !CONTROL.test(value)
  );
}

/* eslint-disable-next-line no-control-regex */
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f]/;

function scalar(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'boolean') return String(value);
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : `'${value}'`;
  if (typeof value === 'string') return quoted(value);
  // Empty containers only — non-empty ones are handled as blocks before reaching here.
  if (Array.isArray(value)) return '[]';
  if (typeof value === 'object') return '{}';
  return quoted(String(value));
}

/** Keys are our own field names, but quoting on the same rules costs nothing and can't be wrong. */
function scalarKey(key: string): string {
  return quoted(key);
}

/** Anything a YAML reader could interpret as other than a plain string. */
const RESERVED = /^(?:~|null|Null|NULL|true|True|TRUE|false|False|FALSE|yes|no|on|off|y|n)$/;
const NUMERIC = /^[-+]?(?:\d[\d_]*(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?$/;
/* Leading `- ? : , [ ] { } # & * ! | > ' " % @ \` are indicators; `%` and `@` only at the start. */
const LEADING_INDICATOR = /^[-?:,[\]{}#&*!|>'"%@`]/;

function quoted(value: string): string {
  if (!needsQuotes(value)) return value;
  // Control characters and backslashes need escapes only double quotes provide; JSON's escaping is
  // a subset of YAML's double-quoted style, so this is safe verbatim.
  // A newline lands here only when a block scalar could not represent the string faithfully, and a
  // single-quoted scalar cannot either — YAML folds line breaks inside one.
  if (/["\\\n]/.test(value) || CONTROL.test(value)) return JSON.stringify(value);
  return `'${value.replace(/'/g, "''")}'`;
}

function needsQuotes(value: string): boolean {
  return (
    value === '' ||
    value !== value.trim() ||
    value.includes(': ') ||
    value.endsWith(':') ||
    value.includes('#') ||
    value.includes('\n') ||
    LEADING_INDICATOR.test(value) ||
    RESERVED.test(value) ||
    NUMERIC.test(value)
  );
}

function isContainer(value: unknown): value is object {
  return typeof value === 'object' && value !== null;
}

function isEmpty(value: object): boolean {
  return Array.isArray(value) ? value.length === 0 : Object.keys(value).length === 0;
}
