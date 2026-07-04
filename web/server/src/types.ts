/**
 * The parsed intent the LLM is asked to return. Mirrors the Android app's
 * VoiceCommandIntent / ActionType exactly (same JSON keys, same allowed values),
 * so the client can consume the server's reply without translation.
 */
export type ActionType = "create_project" | "delete_project" | "add_task" | "remove_task";

export interface VoiceCommandIntent {
  action_type: ActionType;
  project_name: string;
  task_title: string | null;
  due_date: string | null;
}

/** Minimal view of the user's projects sent up by the client for grounding. */
export interface ProjectContext {
  name: string;
  tasks: string[]; // task titles only — everything else is irrelevant to the classifier
}

export interface RepoCreated {
  name: string;
  full_name: string;
  html_url: string;
}

export interface IssueCreated {
  number: number;
  html_url: string;
}
