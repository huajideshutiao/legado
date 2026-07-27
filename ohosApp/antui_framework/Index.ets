import { nativeCreateComposeView, nativeInitFramework } from 'libmykmp_framework.so'
export { KotlinChannel } from './src/main/ets/channel/KotlinChannel'
export { XComponentPage } from './src/main/ets/xcomponent/XComponentPage'
export function initKmpFramework(context: Context) {
  console.info('initKmpFramework');
  nativeInitFramework(context.resourceManager);
}
export function createComposeView(page_create_func: string) {
  nativeCreateComposeView(page_create_func, page_create_func)
}
