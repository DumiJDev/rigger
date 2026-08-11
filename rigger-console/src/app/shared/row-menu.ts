import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
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

/** Viewport coordinates for the open menu. Only one of `top`/`bottom` is ever set. */
interface MenuPosition {
  /** Distance from the viewport's right edge, so the menu's right edge tracks the button's. */
  right: number;
  top?: number;
  bottom?: number;
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
 *
 * <p><b>The menu is positioned `fixed`, not `absolute`, and that is not cosmetic.</b> Every list
 * page wraps its table in `.table-wrap`, which sets `overflow-x: auto` so wide tables scroll rather
 * than break the layout. CSS then promotes the *other* axis from `visible` to `auto` as well —
 * `overflow` cannot be visible on one axis and clipped on the other — so that element clips
 * vertically too and becomes a scroll container. An absolutely positioned menu inside it is clipped
 * to its padding box, which is exactly what "the menu opens inside the table" looked like. Fixed
 * positioning takes the menu out of that box entirely; the price is that its coordinates must be
 * measured from the button and refreshed by hand, which is what the rest of this class does.
 */
@Component({
  selector: 'r-row-menu',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, Icon],
  template: `
    <div class="inline-block" *transloco="let t">
      <button
        #trigger
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

      @if (position(); as pos) {
        <!-- Right-aligned: opening leftwards would run off a narrow viewport. Above the button when
             there is no room below, decided against the viewport — correct now that it is fixed. -->
        <div
          class="surface fixed z-40 min-w-40 py-1 shadow-lg"
          role="menu"
          [style.right.px]="pos.right"
          [style.top.px]="pos.top"
          [style.bottom.px]="pos.bottom"
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
              (click)="pick(a, $event)"
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
  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');

  /** Non-null exactly while the menu is open; it carries where to draw it. */
  readonly position = signal<MenuPosition | null>(null);
  readonly open = signal(false);

  /** Removes the scroll/resize listeners registered for the currently open menu. */
  private detach: (() => void) | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.close());
  }

  /** Room needed below the button for the tallest menu this renders; below that, flip upward. */
  private static readonly MENU_HEIGHT = 180;
  /** Gap between button and menu, matching the `mt-1` the absolute version used. */
  private static readonly GAP = 4;

  toggle(event: MouseEvent): void {
    // The row itself opens the detail drawer on click; a kebab press must not also do that.
    event.stopPropagation();
    if (this.open()) {
      this.close();
      return;
    }

    const rect = this.trigger().nativeElement.getBoundingClientRect();
    const dropUp = window.innerHeight - rect.bottom < RowMenu.MENU_HEIGHT;
    this.position.set({
      right: window.innerWidth - rect.right,
      top: dropUp ? undefined : rect.bottom + RowMenu.GAP,
      bottom: dropUp ? window.innerHeight - rect.top + RowMenu.GAP : undefined,
    });
    this.open.set(true);

    // A fixed menu does not travel with the row it belongs to, so any scroll would leave it pointing
    // at a different resource — a worse failure than the clipping this replaced. Closing is the
    // honest response and avoids a reposition handler on every scroll frame; capture phase because
    // `scroll` does not bubble from the `.table-wrap` scroller.
    const close = () => this.close();
    document.addEventListener('scroll', close, { capture: true });
    window.addEventListener('resize', close);
    this.detach = () => {
      document.removeEventListener('scroll', close, { capture: true });
      window.removeEventListener('resize', close);
    };
  }

  private close(): void {
    this.detach?.();
    this.detach = null;
    this.open.set(false);
    this.position.set(null);
  }

  pick(action: RowAction, event: MouseEvent): void {
    // Fixed positioning moves where the menu paints, not where it sits in the DOM: it is still a
    // descendant of the table row, whose own click handler opens the detail drawer. Without this,
    // choosing Delete would also open the drawer behind the confirmation.
    event.stopPropagation();
    if (action.disabled) return;
    this.close();
    this.selected.emit(action.id);
  }

  /**
   * Closes on any click outside. A menu that stays open while you click elsewhere leaves two open at
   * once, and on a table of rows that reads as a rendering bug.
   *
   * <p>The menu is a child of this host in the DOM even though it renders over the page — fixed
   * positioning changes where an element paints, not where it lives — so `contains` still tells
   * "inside my menu" from "somewhere else".
   */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event): void {
    if (this.open() && !this.host.nativeElement.contains(event.target)) this.close();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.open()) this.close();
  }
}
