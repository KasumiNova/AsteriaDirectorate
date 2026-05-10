export function captureCanvasPng(canvas: HTMLCanvasElement): string {
  return canvas.toDataURL('image/png');
}
