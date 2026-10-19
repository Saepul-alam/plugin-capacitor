export interface WahanaPluginPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
