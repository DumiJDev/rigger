import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { Icon } from './icon';

let nextInstanceId = 0;

export interface KvPair {
  key: string;
  value: string;
}

/**
 * A list of key/value rows with add/remove — the one editing pattern every create form needs
 * (Deployment selector and env vars, Service selector, ConfigMap and Secret data). Purely
 * presentational, like `ListToolbar`/`RowMenu`: it knows nothing about what the pairs mean to its
 * caller, only how to edit the list.
 */
@Component({
  selector: 'r-kv-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="space-y-2">
      @for (pair of pairs(); track $index) {
        <div class="flex gap-2">
          <label class="sr-only" [attr.for]="id() + '-key-' + $index">{{ keyPlaceholder() }}</label>
          <input
            [id]="id() + '-key-' + $index"
            class="input flex-1 font-mono text-xs"
            [placeholder]="keyPlaceholder()"
            [value]="pair.key"
            (input)="updateKey($index, $any($event.target).value)"
            [disabled]="disabled()"
          />
          <label class="sr-only" [attr.for]="id() + '-value-' + $index">{{
            valuePlaceholder()
          }}</label>
          <input
            [id]="id() + '-value-' + $index"
            class="input flex-1 font-mono text-xs"
            [type]="valueType()"
            [placeholder]="valuePlaceholder()"
            [value]="pair.value"
            (input)="updateValue($index, $any($event.target).value)"
            [disabled]="disabled()"
          />
          <button
            type="button"
            class="btn-icon"
            [attr.aria-label]="removeLabel()"
            [disabled]="disabled()"
            (click)="removeAt($index)"
          >
            <r-icon name="x" [size]="14" />
          </button>
        </div>
      }
      <button type="button" class="btn btn-ghost text-xs" [disabled]="disabled()" (click)="add()">
        <r-icon name="plus" [size]="14" />
        {{ addLabel() }}
      </button>
    </div>
  `,
})
export class KvEditor {
  /** Unique per instance so key/value `<label for>` ids never collide across two editors on one page. */
  readonly id = input(`kv-editor-${nextInstanceId++}`);
  readonly pairs = model.required<KvPair[]>();
  readonly keyPlaceholder = input('key');
  readonly valuePlaceholder = input('value');
  readonly valueType = input<'text' | 'password'>('text');
  readonly addLabel = input('Add');
  readonly removeLabel = input('Remove');
  readonly disabled = input(false);

  add(): void {
    this.pairs.update((rows) => [...rows, { key: '', value: '' }]);
  }

  removeAt(index: number): void {
    this.pairs.update((rows) => rows.filter((_, i) => i !== index));
  }

  updateKey(index: number, key: string): void {
    this.pairs.update((rows) => rows.map((row, i) => (i === index ? { ...row, key } : row)));
  }

  updateValue(index: number, value: string): void {
    this.pairs.update((rows) => rows.map((row, i) => (i === index ? { ...row, value } : row)));
  }
}

/** Converts an edited list to a plain map, dropping rows with no key — the "not filled in yet" row. */
export function kvPairsToMap(pairs: KvPair[]): Record<string, string> {
  const map: Record<string, string> = {};
  for (const { key, value } of pairs) {
    if (key.trim()) map[key.trim()] = value;
  }
  return map;
}
