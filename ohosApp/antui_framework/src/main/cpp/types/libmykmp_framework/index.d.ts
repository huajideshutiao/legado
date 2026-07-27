export const nativeInitFramework: (resourceManager: object) => void;
export const CallKotlinFunction: (funcName: string, ...args: any[]) => any;
export const RegisterFunction: (moduleName: string, funcName: string, obj: any) => void;
export const createNativeNodeContent: (viewId: string, nodeContent: object, customComponentBuilder: object | null, dialogManager: object | null) => void;
export const nativeCreateComposeView: (functionName: string, componentId: string) => void;
