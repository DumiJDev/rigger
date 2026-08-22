import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  computed,
  input,
  output,
  viewChild,
} from '@angular/core';

const FOCUSABLE = 'input, select, textarea, button, a[href], [tabindex]:not([tabindex="-1"])';

/**
 * Centered modal shell: backdrop, `role="dialog"`, focus trap, and Escape-to-close in one place.
 *
 * <p>Every create/confirm/scale dialog in the console used to hand-roll this same
 * `fixed inset-0 ... bg-black/40` shape without any of those three, while `DetailDrawer` and
 * `RowMenu` — built the same shape — both got it right. One shared shell means the next dialog
 * inherits the fix instead of the gap.
 */
@Component({
  selector: 'r-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fixed inset-0 z-30 grid place-items-center bg-black/40 px-4" (click)="closed.emit()">
      <div
        #panel
        class="surface w-full p-5"
        [class]="sizeClass()"
        role="dialog"
        aria-modal="true"
        [attr.aria-labelledby]="labelledBy() || null"
        [attr.aria-label]="labelledBy() ? null : ariaLabel() || null"
        tabindex="-1"
        (click)="$event.stopPropagation()"
      >
        <ng-content />
      </div>
    </div>
  `,
})
export class Dialog implements AfterViewInit, OnDestroy {
  readonly size = input<'sm' | 'md' | 'lg'>('sm');
  /** Id of the element (usually the dialog's own heading) that names it for assistive tech. */
  readonly labelledBy = input<string | undefined>(undefined);
  /** Fallback name when the dialog has no heading element to point `labelledBy` at. */
  readonly ariaLabel = input<string | undefined>(undefined);
  readonly closed = output<void>();

  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private previouslyFocused: HTMLElement | null = null;

  readonly sizeClass = computed(
    () => ({ sm: 'max-w-sm', md: 'max-w-md', lg: 'max-w-lg' })[this.size()],
  );

  ngAfterViewInit(): void {
    this.previouslyFocused = document.activeElement as HTMLElement | null;
    const panelEl = this.panel()?.nativeElement;
    const first = panelEl?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? panelEl)?.focus();
  }

  ngOnDestroy(): void {
    this.previouslyFocused?.focus?.();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }

  @HostListener('document:keydown.tab', ['$event'])
  onTab(event: Event): void {
    const keyEvent = event as KeyboardEvent;
    const panelEl = this.panel()?.nativeElement;
    if (!panelEl) return;
    const focusables = Array.from(panelEl.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
      (el) => !el.hasAttribute('disabled'),
    );
    if (!focusables.length) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (keyEvent.shiftKey && document.activeElement === first) {
      keyEvent.preventDefault();
      last.focus();
    } else if (!keyEvent.shiftKey && document.activeElement === last) {
      keyEvent.preventDefault();
      first.focus();
    }
  }
}
