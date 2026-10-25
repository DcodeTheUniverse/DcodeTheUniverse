import { fireEvent, render, screen } from '@solidjs/testing-library';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppContext } from '../../src/app/context';
import { AUTO_COVER_MS, SecretCard } from '../../src/features/game/SecretCard';
import { makeApp } from './harness';

async function renderCard(
  secret: { kind: 'word'; word: string } | { kind: 'bluffer' },
  revealMode: 'hold' | 'tap' = 'hold',
) {
  const h = await makeApp();
  h.app.updateSettings({ revealMode });
  const seen = vi.fn();
  render(() => (
    <AppContext.Provider value={h.app}>
      <SecretCard packName="General" secret={() => secret} onSeen={seen} />
    </AppContext.Provider>
  ));
  return { h, seen };
}

const inDom = (text: string) => document.body.innerHTML.includes(text);

afterEach(() => {
  vi.useRealTimers();
});

describe('SecretCard', () => {
  it('does not mount the secret until an intentional reveal, and unmounts it on release', async () => {
    const { seen } = await renderCard({ kind: 'word', word: 'Umbrella' });
    expect(inDom('Umbrella')).toBe(false);
    const hold = screen.getByRole('button', { name: 'Hold to reveal' });
    fireEvent.pointerDown(hold, { button: 0, pointerId: 1, pointerType: 'touch' });
    expect(inDom('Umbrella')).toBe(true);
    expect(seen).toHaveBeenCalled();
    fireEvent.pointerUp(hold, { pointerId: 1 });
    expect(inDom('Umbrella')).toBe(false);
  });

  it('covers on pointer cancel, window blur, visibility change and the native cover signal', async () => {
    const { h } = await renderCard({ kind: 'bluffer' });
    const hold = () => screen.getByRole('button', { name: /Hold to reveal|Release to cover/ });
    const reveal = () =>
      fireEvent.pointerDown(hold(), { button: 0, pointerId: 1, pointerType: 'touch' });

    reveal();
    expect(inDom('You’re the bluffer.')).toBe(true);
    fireEvent.pointerCancel(hold(), { pointerId: 1 });
    expect(inDom('You’re the bluffer.')).toBe(false);

    reveal();
    fireEvent.blur(window);
    expect(inDom('You’re the bluffer.')).toBe(false);

    reveal();
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    fireEvent(document, new Event('visibilitychange'));
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    expect(inDom('You’re the bluffer.')).toBe(false);

    reveal();
    h.fake.background(); // Capacitor pause/appStateChange path through the controller
    expect(inDom('You’re the bluffer.')).toBe(false);
  });

  it('never puts the secret in attributes or accessible labels', async () => {
    await renderCard({ kind: 'word', word: 'Kettle' });
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Hold to reveal' }), {
      button: 0,
      pointerId: 1,
    });
    const attrs = Array.from(document.querySelectorAll('*')).flatMap((el) =>
      Array.from(el.attributes).map((a) => a.value),
    );
    expect(attrs.join(' ')).not.toContain('Kettle');
  });

  it('supports keyboard hold (Space) as well as pointer hold', async () => {
    await renderCard({ kind: 'word', word: 'Kettle' });
    const hold = screen.getByRole('button', { name: 'Hold to reveal' });
    fireEvent.keyDown(hold, { key: ' ' });
    expect(inDom('Kettle')).toBe(true);
    fireEvent.keyUp(hold, { key: ' ' });
    expect(inDom('Kettle')).toBe(false);
  });

  it('tap mode reveals explicitly and auto-covers after the timeout', async () => {
    await renderCard({ kind: 'word', word: 'Kettle' }, 'tap');
    vi.useFakeTimers();
    fireEvent.click(screen.getByRole('button', { name: 'Show my card' }));
    expect(inDom('Kettle')).toBe(true);
    vi.advanceTimersByTime(AUTO_COVER_MS - 100);
    expect(inDom('Kettle')).toBe(true);
    vi.advanceTimersByTime(200);
    expect(inDom('Kettle')).toBe(false);
    // Can reveal again any number of times.
    fireEvent.click(screen.getByRole('button', { name: 'Show my card' }));
    expect(inDom('Kettle')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Hide my card' }));
    expect(inDom('Kettle')).toBe(false);
  });

  it('shows informed and bluffer faces with the same structure', async () => {
    const a = await renderCard({ kind: 'word', word: 'Kettle' });
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Hold to reveal' }), {
      button: 0,
      pointerId: 1,
    });
    const wordClasses = Array.from(document.querySelectorAll('p')).map((p) => p.className);
    a.h.dispose();
    document.body.innerHTML = '';
    await renderCard({ kind: 'bluffer' });
    fireEvent.pointerDown(screen.getAllByRole('button', { name: 'Hold to reveal' })[0]!, {
      button: 0,
      pointerId: 1,
    });
    const blufferClasses = Array.from(document.querySelectorAll('p')).map((p) => p.className);
    expect(blufferClasses).toEqual(wordClasses);
  });
});
