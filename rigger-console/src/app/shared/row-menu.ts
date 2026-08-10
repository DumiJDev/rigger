import { ChangeDetectionStrategy, Component, ElementRef, HostListener, computed, inject, input, output, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { Icon, IconName } from './icon';

export interface RowAction {
  /** Returned by {@link RowMenu.selected} when picked. */
  id: string;
  /** Translation key, resolved here so callers pass keys rather than pre-translated strings. */
  labelKey: string;
  icon?: IconName;
  /** Renders in the error colour and is separated from the rest. For deletes. */
  danger?: boolean;
  disabled?: boolean;
}

/**
 * Per-row actions behind a kebab, replacing the loose buttons that sat in every row.
 *
 * <p>Why it matters beyond looks: a row with "Scale" and "Delete" spelled out puts a destructive
 * button one mis-click from the row you were reading, and the widest action label sets the width of
 * a column that exists only for buttons. A kebab is a fixed 24px and the destructive item sits
 * behind one deliberate click.
 *
 * <p>Callers pass the actions they are allowed to offer — filtering by `auth.can(...)` stays with
 * the page, since only it knows the resource kind.
 */
@Component({
  selector: 'r-row-menu',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, Icon],
  template: `
    <div class="relative inline-block" *transloco="let t">
      <button
        type="button"
        class="btn-icon"
        [disabled]="disabled() || !actions().length"
        [attr.aria-label]="t('common.actions')"
        [attr.aria-expanded]="open()"
        aria-haspopup="menu"
        (click)="toggle($event)"
      >
        <r-icon name="more" [size]="15" />
      </button>

      @if (open()) {
        <!-- Right-aligned and above the row: opening leftwards would run off a narrow viewport. -->
        <div
          class="surface absolute right-0 z-30 mt-1 min-w-40 py-1 shadow-lg"
          role="menu"
          [class.bottom-full]="dropUp()"
          [class.mb-1]="dropUp()"
        >
          @for (a of actions(); track a.id) {
            @if (a.danger) {
              <div class="my-1 border-t" style="border-color: var(--border-subtle)"></div>
            }
            <button
              type="button"
              role="menuitem"
              class="menu-item"
              [class.menu-item-danger]="a.danger"
              [disabled]="a.disabled"
              (click)="pick(a)"
            >
              @if (a.icon) {
                <r-icon [name]="a.icon" [size]="14" />
              }
              {{ t(a.labelKey) }}
            </button>
          }
        </div>
      }
    </div>
  `,
})
export class RowMenu {
  readonly actions = input<RowAction[]>([]);
  readonly disabled = input(false);
  readonly selected = output<string>();

  private readonly host = inject(ElementRef<HTMLElement>);
  readonly open = signal(false);

  /** Opens upward for rows near the bottom of the viewport, where a downward menu would be clipped. */
  private readonly openedNearBottom = signal(false);
  readonly dropUp = computed(() => this.openedNearBottom());

  toggle(event: MouseEvent): void {
    event.stopPropagation();
    const rect = (this.host.nativeElement as HTMLElement).getBoundingClientRect();
    // Enough room for the tallest menu this renders; below that, flip.
    this.openedNearBottom.set(window.innerHeight - rect.bottom < 180);
    this.open.update((v) => !v);
  }

  pick(action: RowAction): void {
    if (action.disabled) return;
    this.open.set(false);
    this.selected.emit(action.id);
  }

  /**
   * Closes on any click outside. A menu that stays open while you click elsewhere leaves two open at
   * once, and on a table of rows that reads as a rendering bug.
   */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event): void {
    if (this.open() && !this.host.nativeElement.contains(event.target)) this.open.set(false);
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.open.set(false);
  }
}
